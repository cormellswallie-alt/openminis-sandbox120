package com.openminis.app.sandbox

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalStartupPolicyTest {
    @Test fun `cwd args prepare guest directory and preserve argv boundaries`() {
        val root = Files.createTempDirectory("terminal root with spaces ").toFile()
        try {
            assertEquals(listOf("-w", "/var/minis"), TerminalStartupPolicy.workingDirectoryArgs(root, "chat"))
            assertTrue(root.resolve("var/minis").isDirectory)
            assertEquals(listOf("-w", "/root"), TerminalStartupPolicy.workingDirectoryArgs(root, null))
            assertTrue(root.resolve("root").isDirectory)
            root.resolve("var/minis").deleteRecursively()
            root.resolve("var/minis").writeText("blocked")
            try {
                TerminalStartupPolicy.workingDirectoryArgs(root, "chat")
                fail("Must reject an unusable cwd rather than allow proot's / fallback")
            } catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun `real login interactive shell preserves cwd and queued commands`() {
        val root = Files.createTempDirectory("terminal shell space ").toFile()
        try {
            val process = ProcessBuilder(TerminalStartupPolicy.shellArgs)
                .directory(root).redirectError(root.resolve("shell-stderr.log")).start()
            // Queue user commands immediately, without any startup input or delay.
            process.outputStream.bufferedWriter().use {
                it.write("pwd\nprintf 'first user output\\n'\nprintf 'second user output\\n'\nexit\n")
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(listOf(root.canonicalPath, "first user output", "second user output"),
                process.inputStream.bufferedReader().readLines())
        } finally { root.deleteRecursively() }
    }

    @Test fun `banner does not block sibling first output and is cancelled on disposal`() = runBlocking {
        val banner = CompletableDeferred<Unit>()
        val job = launch { TerminalStartupPolicy.emitBanner({ true }) { banner.complete(Unit) } }
        val output = async { "first PTY output" }
        assertEquals("first PTY output", withTimeout(200) { output.await() })
        assertFalse(banner.isCompleted)
        job.cancelAndJoin()
        assertFalse(banner.isCompleted)
    }

    @Test fun `old generation cannot emit delayed banner into restarted terminal`() = runBlocking {
        val gate = TerminalLifecycleGate()
        val old = gate.begin()!!
        var emissions = 0
        val job = launch { TerminalStartupPolicy.emitBanner({ gate.isCurrent(old) }) { emissions++ } }
        yield()
        gate.invalidate()
        val current = gate.begin()!!
        job.join()
        assertEquals(0, emissions)
        assertTrue(gate.isCurrent(current))
        TerminalStartupPolicy.emitBanner({ gate.isCurrent(current) }) { emissions++ }
        assertEquals(1, emissions)
    }
}
