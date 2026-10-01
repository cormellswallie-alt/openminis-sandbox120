package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProcSnapshotTest {
    @Test fun `snapshot skips unrelated and vanished processes and keeps postorder`() {
        val proc = kotlin.io.path.createTempDirectory("proc-snapshot").toFile()
        try {
            fun stat(pid: Int, parent: Int, comm: String = "sh") {
                File(proc, "$pid").mkdirs()
                File(proc, "$pid/stat").writeText("$pid ($comm) S $parent 0 0")
            }
            stat(100, 1)
            stat(200, 100, "sh (a) b)")
            stat(201, 200)
            stat(300, 100)
            stat(400, 1)
            File(proc, "self").mkdirs()
            File(proc, "500").mkdirs()
            val descendants = ProcSnapshot.descendants(ProcSnapshot.children(proc), 100)
            assertEquals(setOf(200, 201, 300), descendants.toSet())
            assertTrue(descendants.indexOf(201) < descendants.indexOf(200))
        } finally { proc.deleteRecursively() }
    }

    @Test fun `depth matches the previous fresh cleanup limit`() {
        val tree = (100..110).associateWith { listOf(it + 1) }
        assertEquals((101..109).reversed().toList(), ProcSnapshot.descendants(tree, 100, 9))
        assertEquals(emptyList<Int>(), ProcSnapshot.descendants(tree, 100, 0))
    }

    @Test fun `cycles never signal the root or duplicate descendants`() {
        val tree = mapOf(1 to listOf(2), 2 to listOf(3), 3 to listOf(1, 2))
        assertEquals(listOf(3, 2), ProcSnapshot.descendants(tree, 1))
    }

    @Test fun `parent parsing tolerates comm parentheses and rejects malformed fields`() {
        assertEquals(7, ProcSnapshot.parseParentPid("12 (a) b) c) S 7 12 12"))
        assertEquals(7, ProcSnapshot.parseParentPid("12 (sh)\tS\t7\t0"))
        for (text in listOf("garbage", "12 (sh)", "12 (sh) S", "12 (sh) S nope", "12 (sh) S 2147483648")) {
            assertNull(ProcSnapshot.parseParentPid(text))
        }
    }
}
