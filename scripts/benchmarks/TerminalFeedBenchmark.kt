package com.openminis.app.ui.terminal.emulator

import java.lang.management.ManagementFactory

/** Desktop only: compile with run-terminal-feed-benchmark.sh; never Android test sources. */
object TerminalFeedBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val random = java.util.Random(90210)
        val em = TerminalEmulator(20, 6)
        var digest = 1L
        val responses = mutableListOf<String>()
        em.onResponse = { responses.add(it.toString(Charsets.UTF_8)) }
        val sequences = listOf("abcXYZ 012", "中文😀é", "\r\n", "\u001b[31mred\u001b[0m",
            "\u001b[38;2;12;34;56mRGB", "\u001b[2J", "\u001b[2;3H", "\u001b[2S", "\u001b[1T",
            "\u001b[2L", "\u001b[2M", "\u001b[3P", "\u001b[2@", "\u001b[?1049hALT\u001b[?1049l",
            "\u001b[6n", "\u001b[5n", "\u001b]2;title\u0007", "\u001b]1337;MinisOpenURL=https://example.test/\u0007")
        repeat(3000) { iteration ->
            val data = sequences[random.nextInt(sequences.size)].toByteArray()
            var offset = 0
            while (offset < data.size) {
                val end = minOf(data.size, offset + 1 + random.nextInt(7))
                em.feed(data.copyOfRange(offset, end)); offset = end
            }
            if (iteration % 13 == 0) em.resize(1 + random.nextInt(30), 1 + random.nextInt(10))
            em.scrollOffset = random.nextInt(em.activeBuffer.scrollback.size + 1)
            em.setSelectionRect(0, 0, em.cols - 1, em.rows - 1)
            for (buffer in listOf(em.primaryBuffer, em.alternateBuffer)) {
                for (row in buffer.scrollback + buffer.grid.toList()) for (cell in row) {
                    // Stable values, never identity hash of Default color.
                    digest = digest * 31 + cell.toString().replace(Regex("Default@[0-9a-f]+"), "Default").hashCode()
                }
                digest = digest * 31 + buffer.cursorCol
                digest = digest * 31 + buffer.cursorRow
                digest = digest * 31 + buffer.scrollTop
                digest = digest * 31 + buffer.scrollBottom
                digest = digest * 31 + buffer.wrapPending.hashCode()
            }
            digest = digest * 31 + em.getSelectedText(0, 0, em.cols - 1, em.rows - 1).hashCode()
            digest = digest * 31 + em.isAlternateActive.hashCode()
        }
        println("differential seed=90210 operations=3000 digest=$digest responses=${responses.hashCode()} urls=${com.openminis.app.terminal.MinisOpenUrlBroker.urls.hashCode()}")
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val workloads = linkedMapOf(
            "plain" to "build src/main/file.kt: completed successfully 0123456789\r\n",
            "ansi" to "\u001b[32mPASS\u001b[0m \u001b[38;2;10;20;30mcompile file\u001b[0m 0123456789\r\n",
            "unicode" to "编译完成 中文 😀 café é 0123456789\r\n",
            "alternate" to "\u001b[?1049h\u001b[Hstatus: running 0123456789\u001b[K\u001b[?1049l\r\n"
        )
        for ((name, line) in workloads) {
            val bytes = line.repeat(20000).toByteArray()
            fun run(): Long {
                val em = TerminalEmulator(80, 24)
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + 4096)
                    em.feed(bytes.copyOfRange(offset, end))
                    offset = end
                }
                return em.primaryBuffer.scrollback.size.toLong() + em.activeBuffer.grid.sumOf { row -> row.sumOf { it.char.toLong() } }
            }
            repeat(8) { run() }
            val times = mutableListOf<Double>(); val allocations = mutableListOf<Long>(); var checksum = 0L
            repeat(9) {
                val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                val start = System.nanoTime()
                checksum = run()
                times.add((System.nanoTime() - start) / 1e6)
                allocations.add(bean.getThreadAllocatedBytes(Thread.currentThread().id) - allocated)
            }
            println("$name bytes=${bytes.size} medianMs=${"%.3f".format(times.sorted()[4])} allocatedBytes=${allocations.sorted()[4]} checksum=$checksum")
        }
    }
}
