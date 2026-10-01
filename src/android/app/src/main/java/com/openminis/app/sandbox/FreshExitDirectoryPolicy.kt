package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Shared by shells using the same real directory; active files never age out. */
internal class FreshExitDirectoryPolicy(
    private val nanoClock: () -> Long = System::nanoTime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val intervalNanos: Long = 300_000_000_000L,
    private val staleMillis: Long = 3_600_000L,
    private val listFiles: (File) -> Array<File>? = { it.listFiles() },
    private val scheduler: Executor = Executor { it.run() },
) {
    private class State {
        val active = mutableMapOf<String, Int>()
        var scannedAt: Long? = null
        var pending = false // Covers both queued and running maintenance.
        var generation = 0L
    }
    // Never replace a state on recreation: existing handles still protect this path.
    private val directories = ConcurrentHashMap<String, State>()

    fun register(directory: File, token: String): AutoCloseable {
        require(TOKEN.matches(token))
        val dir = directory.canonicalFile
        val state = directories.computeIfAbsent(dir.path) { State() }
        val schedule = synchronized(state) {
            state.active[token] = (state.active[token] ?: 0) + 1
            runCatching {
                if (!dir.isDirectory) {
                    state.generation++
                    state.scannedAt = null
                    check(dir.mkdirs() || dir.isDirectory) { "Cannot create exit directory: $dir" }
                }
            }
            runCatching {
                val previous = state.scannedAt
                if (!state.pending && (previous == null || nanoClock() - previous >= intervalNanos)) {
                    state.pending = true
                    true
                } else false
            }.getOrDefault(false)
        }
        // Submission must also be outside the monitor, including for an inline executor.
        if (schedule) {
            runCatching { scheduler.execute { maintain(dir, state) } }.onFailure {
                synchronized(state) { state.pending = false }
            }
        }
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(state) {
                if (!closed) {
                    closed = true
                    val remaining = state.active.getValue(token) - 1
                    if (remaining == 0) {
                        runCatching { File(dir, token).delete() }
                        runCatching { File(dir, "$token.pid").delete() }
                        state.active.remove(token)
                    } else state.active[token] = remaining
                }
            }
        }
    }

    private fun maintain(dir: File, state: State) {
        val generation = synchronized(state) { state.generation }
        var completedAt: Long? = null
        try {
            val files = checkNotNull(listFiles(dir)) { "Cannot list exit directory: $dir" }
            val cutoff = wallClock() - staleMillis
            for (file in files) {
                val name = file.name.removeSuffix(".pid")
                // Directory enumeration and metadata I/O never hold the state monitor.
                if (TOKEN.matches(name) && file.isFile && file.lastModified() < cutoff) {
                    synchronized(state) {
                        if (state.generation == generation && name !in state.active) file.delete()
                    }
                }
            }
            completedAt = nanoClock()
        } catch (_: Exception) {
            // Best effort; next registration can retry a failed scan.
        } finally {
            synchronized(state) {
                if (state.generation == generation && completedAt != null) state.scannedAt = completedAt
                state.pending = false
            }
        }
    }

    companion object {
        private val TOKEN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val shared = FreshExitDirectoryPolicy(scheduler = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fresh-exit-maintenance").apply { isDaemon = true }
        })
    }
}
