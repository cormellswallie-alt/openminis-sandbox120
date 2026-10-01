package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FreshExitDirectoryPolicyTest {
    private fun token() = UUID.randomUUID().toString()
    private fun old(dir: File, name: String): File = File(dir, name).apply {
        writeText("123")
        assertTrue(setLastModified(1L))
    }
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("fresh-exit-test").toFile()
        try { block(File(root, "exit")) } finally { root.deleteRecursively() }
    }

    @Test fun onlyValidInactiveFilesArePrunedAndLongRunningSurvives() = temporary { dir ->
        var now = 0L
        val scans = AtomicInteger()
        val policy = FreshExitDirectoryPolicy({ now }, { 10_000_000L }, 100L,
            listFiles = { scans.incrementAndGet(); it.listFiles() })
        val active = token()
        val handle = policy.register(dir, active)
        val pid = old(dir, "$active.pid")
        val status = old(dir, active)
        val stale = old(dir, token())
        val stalePid = old(dir, "${token()}.pid")
        val unrelated = old(dir, "notes.pid")
        val extraSuffix = old(dir, "${token()}.status")
        val subdir = File(dir, token()).apply { mkdir(); setLastModified(1L) }
        now = 200L
        policy.register(dir, token()).close()
        assertTrue(pid.exists()); assertTrue(status.exists())
        assertFalse(stale.exists()); assertFalse(stalePid.exists())
        assertTrue(unrelated.exists()); assertTrue(extraSuffix.exists()); assertTrue(subdir.exists())
        handle.close(); handle.close()
        assertFalse(pid.exists()); assertFalse(status.exists())
        old(dir, active)
        now = 400L
        policy.register(dir, token()).close()
        assertFalse(File(dir, active).exists())
        assertEquals(3, scans.get())
    }

    @Test fun concurrentAliasesShareOneScan() = temporary { dir ->
        val scans = AtomicInteger()
        val policy = FreshExitDirectoryPolicy({ 0L }, listFiles = {
            scans.incrementAndGet(); it.listFiles()
        })
        dir.mkdirs()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 200).map { index -> pool.submit {
                val alias = if (index % 2 == 0) dir else File(dir, "../exit")
                policy.register(alias, token()).use { }
            } }
            jobs.forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(1, scans.get())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun missingDirectoryIsRecreatedAtSamePathBeforeInterval() = temporary { dir ->
        val scans = AtomicInteger()
        val policy = FreshExitDirectoryPolicy({ 0L }, listFiles = {
            scans.incrementAndGet(); it.listFiles()
        })
        policy.register(dir, token()).close()
        assertTrue(dir.delete())
        policy.register(dir, token()).close()
        assertTrue(dir.isDirectory)
        assertEquals(2, scans.get())
    }

    @Test fun failedListingRetriesAndFinallyReleasesRegistration() = temporary { dir ->
        var now = 0L
        val attempts = AtomicInteger()
        val policy = FreshExitDirectoryPolicy({ now }, { 10_000_000L }, 100L,
            listFiles = { if (attempts.incrementAndGet() == 1) error("scan failed") else it.listFiles() })
        val id = token()
        try {
            policy.register(dir, id).use {
                old(dir, id); old(dir, "$id.pid")
                error("spawn failed / stop early")
            }
        } catch (_: IllegalStateException) { }
        assertFalse(File(dir, id).exists())
        assertFalse(File(dir, "$id.pid").exists())
        old(dir, id)
        policy.register(dir, token()).close()
        assertFalse(File(dir, id).exists())
        assertEquals(2, attempts.get())
        now = 100L
        policy.register(dir, token()).close()
        assertEquals(3, attempts.get())
    }

    @Test fun failedCreationRecoversAndDoesNotRetainActiveToken() = temporary { dir ->
        dir.writeText("blocking file")
        val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L })
        val id = token()
        policy.register(dir, id).close()
        assertTrue(dir.delete())
        dir.mkdir()
        val stale = old(dir, id)
        policy.register(dir, token()).close()
        assertFalse(stale.exists())
    }

    private class QueueScheduler : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun queuedAliasesAndDuplicateTokensKeepOneMaintenance() = temporary { dir ->
        val queue = QueueScheduler()
        val scans = AtomicInteger()
        val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L },
            listFiles = { scans.incrementAndGet(); it.listFiles() }, scheduler = queue)
        val id = token()
        val first = policy.register(dir, id)
        val second = policy.register(File(dir, "../exit"), id)
        val file = old(dir, id)
        repeat(200) { policy.register(dir, token()).close() }
        assertEquals(0, scans.get()); assertEquals(1, queue.tasks.size)
        first.close(); first.close()
        assertTrue(file.exists())
        queue.drain()
        assertEquals(1, scans.get()); assertTrue(file.exists())
        second.close(); assertFalse(file.exists())
    }

    @Test fun blockedRealListingDoesNotBlockFirstRegisterOrConcurrentActiveToken() = temporary { dir ->
        dir.mkdirs()
        val id = token()
        val file = old(dir, id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        try {
            val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L }, listFiles = {
                scans.incrementAndGet()
                val actual = it.listFiles() // Real enumeration before the simulated slow return.
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                actual
            }, scheduler = worker)
            val first = callers.submit<AutoCloseable> { policy.register(dir, token()) }
                .get(2, TimeUnit.SECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val active = callers.submit<AutoCloseable> { policy.register(File(dir, "../exit"), id) }
                .get(2, TimeUnit.SECONDS)
            callers.submit { first.close() }.get(2, TimeUnit.SECONDS)
            repeat(200) { policy.register(dir, token()).close() }
            release.countDown()
            worker.submit { }.get(10, TimeUnit.SECONDS)
            assertEquals(1, scans.get()); assertTrue(file.exists())
            active.close(); assertFalse(file.exists())
        } finally {
            release.countDown(); callers.shutdownNow(); worker.shutdownNow()
        }
    }

    @Test fun metadataOutsideLockRechecksActiveBeforeDeletion() = temporary { dir ->
        dir.mkdirs()
        val id = token()
        val stale = old(dir, id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val candidate = object : File(stale.path) {
                override fun lastModified(): Long {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.lastModified()
                }
            }
            val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L },
                listFiles = { arrayOf(candidate) }, scheduler = worker)
            val trigger = policy.register(dir, token())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val active = caller.submit<AutoCloseable> { policy.register(dir, id) }
                .get(2, TimeUnit.SECONDS)
            release.countDown()
            worker.submit { }.get(10, TimeUnit.SECONDS)
            assertTrue(stale.exists())
            active.close(); trigger.close()
            assertFalse(stale.exists())
        } finally { release.countDown(); caller.shutdownNow(); worker.shutdownNow() }
    }

    @Test fun recreationDuringScanPreservesStateAndInvalidatesCompletion() = temporary { dir ->
        val queue = QueueScheduler()
        val scans = AtomicInteger()
        lateinit var policy: FreshExitDirectoryPolicy
        val id = token()
        var newHandle: AutoCloseable? = null
        policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L }, listFiles = {
            val snapshot = it.listFiles()
            if (scans.incrementAndGet() == 1) {
                assertTrue(dir.deleteRecursively())
                newHandle = policy.register(File(dir, "../exit"), id)
                old(dir, id)
            }
            snapshot
        }, scheduler = queue)
        val original = policy.register(dir, id)
        queue.drain()
        assertEquals(1, scans.get())
        original.close()
        assertTrue(File(dir, id).exists())
        policy.register(dir, token()).close()
        assertEquals(1, queue.tasks.size)
        queue.drain()
        assertEquals(2, scans.get()); assertTrue(File(dir, id).exists())
        newHandle!!.close(); assertFalse(File(dir, id).exists())
    }

    @Test fun asyncFailureAndRejectedSubmissionAllowRetry() = temporary { dir ->
        val queue = QueueScheduler()
        val attempts = AtomicInteger()
        var reject = true
        val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L }, listFiles = {
            if (attempts.incrementAndGet() == 1) null else it.listFiles()
        }, scheduler = Executor {
            if (reject) throw RejectedExecutionException("test")
            queue.execute(it)
        })
        policy.register(dir, token()).close()
        reject = false
        policy.register(dir, token()).close(); queue.drain()
        val stale = old(dir, token())
        policy.register(dir, token()).close(); queue.drain()
        assertEquals(2, attempts.get()); assertFalse(stale.exists())
        policy.register(dir, token()).close(); assertTrue(queue.tasks.isEmpty())
    }

    @Test fun tenThousandFilesRepeatedRegistrationScansOnce() = temporary { dir ->
        dir.mkdirs()
        repeat(10_000) { old(dir, token()) }
        val scans = AtomicInteger()
        val queue = QueueScheduler()
        val policy = FreshExitDirectoryPolicy({ 0L }, { 10_000_000L }, listFiles = {
            scans.incrementAndGet(); it.listFiles()
        }, scheduler = queue)
        val first = System.nanoTime()
        policy.register(dir, token()).close()
        val repeated = System.nanoTime()
        repeat(1_000) { policy.register(dir, token()).close() }
        val done = System.nanoTime()
        assertEquals(0, scans.get()); assertEquals(1, queue.tasks.size)
        queue.drain()
        val cleaned = System.nanoTime()
        println("exit directory: 10000 stale first=${(repeated-first)/1e6}ms, 1000 repeated=${(done-repeated)/1e6}ms, maintenance=${(cleaned-done)/1e6}ms, scans=${scans.get()}")
        assertEquals(1, scans.get()); assertEquals(0, dir.listFiles()!!.size)
    }
}
