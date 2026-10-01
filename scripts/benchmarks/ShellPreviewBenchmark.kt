package com.openminis.app.ui.chat

import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import kotlinx.coroutines.*

object ShellPreviewBenchmark {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        try {
            for (width in listOf(80, 4096)) {
                val line = "x".repeat(width)
                val count = if (width == 80) 20_000 else 1_000
                repeat(2) { round ->
                    var old = ""
                    var bytes = bean.getThreadAllocatedBytes(threadId)
                    var start = System.nanoTime()
                    repeat(count) {
                        val updated = if (old.isEmpty()) line else "$old\n$line"
                        old = updated.lines().takeLast(50).joinToString("\n")
                    }
                    val oldMs = (System.nanoTime() - start) / 1_000_000
                    val oldBytes = bean.getThreadAllocatedBytes(threadId) - bytes
                    val p = ShellOutputPreview(this, dispatcher) {}
                    bytes = bean.getThreadAllocatedBytes(threadId)
                    start = System.nanoTime()
                    repeat(count) { p.onLine(line) }
                    val newMs = (System.nanoTime() - start) / 1_000_000
                    val newBytes = bean.getThreadAllocatedBytes(threadId) - bytes
                    check(p.snapshot().length <= 16 * 1024)
                    p.stop()
                    if (round == 1) println("lines=$count width=$width old=${oldMs}ms/${oldBytes}B new=${newMs}ms/${newBytes}B producer allocation; UI allocations excluded")
                }
            }
        } finally { dispatcher.close() }
    }
}
