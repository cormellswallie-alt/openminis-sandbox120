package com.openminis.app.sandbox

/** Standalone JVM benchmark, paired alternating runs; no Gradle required. */
object ShellOutputBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        for (lines in listOf(false, true)) {
            val chunk = (if (lines) "abc甲😀\n".repeat(1200) else "甲😀abcdef".repeat(1000)).take(8192).toCharArray()
            fun run(direct: Boolean): Long {
                val output = StringBuilder()
                val buffer = ShellLineBuffer()
                var checksum = 0L
                repeat(2048) {
                    if (direct) {
                        output.append(chunk, 0, chunk.size)
                        if (lines) buffer.feed(chunk, 0, chunk.size) { checksum += it.length }
                    } else {
                        val text = String(chunk)
                        output.append(text)
                        if (lines) buffer.feed(text) { checksum += it.length }
                    }
                }
                return output.length.toLong() + checksum
            }
            repeat(6) { check(run(false) == run(true)) }
            val times = Array(2) { ArrayList<Double>() }
            val bytes = Array(2) { ArrayList<Long>() }
            repeat(9) { round ->
                for (mode in if (round % 2 == 0) listOf(0, 1) else listOf(1, 0)) {
                    val allocated = bean.getThreadAllocatedBytes(id)
                    val start = System.nanoTime()
                    val sum = run(mode == 1)
                    times[mode].add((System.nanoTime() - start) / 1e6)
                    bytes[mode].add(bean.getThreadAllocatedBytes(id) - allocated)
                    check(sum > 0)
                }
            }
            for (mode in 0..1) println("lines=$lines direct=${mode == 1} medianMs=${times[mode].sorted()[4]} allocatedBytes=${bytes[mode].sorted()[4]}")
        }
    }
}
