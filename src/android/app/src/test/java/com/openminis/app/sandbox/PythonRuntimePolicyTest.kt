package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PythonRuntimePolicyTest {
    @Test fun defaultsPreserveUserValuesAndTerminal() {
        val env = mutableMapOf("TERM" to "xterm-256color")
        PythonRuntimePolicy.applyDefaults(env)
        assertEquals("1", env["PYTHONUNBUFFERED"])
        assertEquals("1", env["PYTHONDONTWRITEBYTECODE"])
        assertEquals("xterm-256color", env["TERM"])
        for (value in listOf("", "0", "custom")) {
            env["PYTHONUNBUFFERED"] = value
            env["PYTHONDONTWRITEBYTECODE"] = value
            repeat(3) { PythonRuntimePolicy.applyDefaults(env) }
            assertEquals(value, env["PYTHONUNBUFFERED"])
            assertEquals(value, env["PYTHONDONTWRITEBYTECODE"])
        }
    }

    @Test fun commandDefaultsAreScopedAndDoNotMutateTerminalEnvironment() {
        val base = mapOf("TERM" to "xterm-256color", "PYTHONDONTWRITEBYTECODE" to "1")
        assertEquals(emptyMap<String, String>(), PythonRuntimePolicy.forCommand(base, emptyMap(), false))
        val streaming = PythonRuntimePolicy.forCommand(base, emptyMap(), true)
        assertEquals(mapOf("PYTHONUNBUFFERED" to "1"), streaming)
        assertFalse(base.containsKey("PYTHONUNBUFFERED"))
        for (value in listOf("", "0", "custom")) {
            val user = mapOf("PYTHONUNBUFFERED" to value)
            assertEquals(user, PythonRuntimePolicy.forCommand(base, user, true))
            assertEquals(user, PythonRuntimePolicy.forCommand(base, user, false))
            assertEquals(emptyMap<String, String>(), PythonRuntimePolicy.forCommand(base + user, emptyMap(), true))
        }
    }

    private fun handshake(flags: List<String>, override: String?, immediate: Boolean) {
        val script = "import sys; print('FIRST'); sys.stderr.write('READY\\n'); sys.stderr.flush(); sys.stdin.readline(); print('LAST')"
        val builder = ProcessBuilder(listOf("python3") + flags + listOf("-c", script))
        builder.environment().apply {
            keys.filter { it.startsWith("PYTHON") }.toList().forEach { remove(it) }
            PythonRuntimePolicy.applyDefaults(this)
            if (override != null) put("PYTHONUNBUFFERED", override)
        }
        val process = builder.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            // READY proves print has executed and the child is waiting for input.
            val ready = executor.submit<String> { process.errorStream.bufferedReader().readLine() }
            assertEquals("READY", ready.get(10, TimeUnit.SECONDS))
            val stdout = process.inputStream.bufferedReader()
            if (immediate) {
                val first = executor.submit<String> { stdout.readLine() }
                assertEquals("FIRST", first.get(10, TimeUnit.SECONDS))
            } else {
                assertEquals("stdout must remain buffered while waiting for input", 0, process.inputStream.available())
            }
            process.outputStream.write("go\n".toByteArray())
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(if (immediate) "LAST\n" else "FIRST\nLAST\n", stdout.readText())
        } finally {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test fun defaultStreamsBeforeInputHandshake() = handshake(emptyList(), null, true)
    @Test fun emptyUserValueRestoresBuffering() = handshake(emptyList(), "", false)
    @Test fun explicitUserValueWins() = handshake(emptyList(), "yes", true)
    @Test fun cliIgnoreEnvironmentWins() {
        handshake(listOf("-E"), null, false)
        handshake(listOf("-I"), null, false)
        handshake(listOf("-E", "-u"), "", true)
    }
    @Test fun repeatedShellsKeepIndependentOverrides() {
        handshake(emptyList(), "", false)
        handshake(emptyList(), null, true)
        handshake(emptyList(), "", false)
    }
    @Test fun cancellationReapsChildWithoutWorker() {
        repeat(3) {
            val builder = ProcessBuilder("python3", "-c", "import sys; sys.stderr.write('READY\\n'); sys.stderr.flush(); sys.stdin.readline()")
            PythonRuntimePolicy.applyDefaults(builder.environment())
            val child = builder.start()
            try {
                assertEquals("READY", child.errorStream.bufferedReader().readLine())
            } finally {
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            }
        }
    }
}
