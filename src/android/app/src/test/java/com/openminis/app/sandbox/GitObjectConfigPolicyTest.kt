package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GitObjectConfigPolicyTest {
    private fun withRoot(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("git-object-policy-").toFile()
        try {
            val config = File(root, "etc/gitconfig")
            config.parentFile.mkdirs()
            block(root, config)
        } finally { root.deleteRecursively() }
    }

    private fun git(root: File, vararg args: String): String {
        val builder = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_CONFIG") || it in listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES") }.toList().forEach { remove(it) }
            put("GIT_CONFIG_NOSYSTEM", "1")
            // Read the migrated file as global config, isolating host system and user config.
            put("GIT_CONFIG_GLOBAL", File(root, "etc/gitconfig").absolutePath)
            put("GIT_AUTHOR_NAME", "Policy Test")
            put("GIT_AUTHOR_EMAIL", "policy@example.test")
            put("GIT_COMMITTER_NAME", "Policy Test")
            put("GIT_COMMITTER_EMAIL", "policy@example.test")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.waitFor())
        return output.trim()
    }

    private fun exerciseRepository(root: File, expected: String) {
        assertEquals(expected, git(root, "config", "--get", "core.createObject"))
        git(root, "init", "--quiet")
        File(root, "payload").writeText("object migration\n")
        git(root, "add", "payload")
        git(root, "diff", "--cached", "--check")
        git(root, "commit", "--quiet", "-m", "verify object creation")
        val blob = git(root, "hash-object", "-w", "payload")
        assertEquals(blob, git(root, "rev-parse", "HEAD:payload"))
        assertEquals("object migration", git(root, "cat-file", "-p", blob))
        git(root, "fsck", "--strict")
        git(root, "diff", "--check")
        if (expected == "rename") {
            assertFalse(root.walkTopDown().any { it.name.startsWith(".l2s.") })
        }
    }

    @Test fun migratesCopyAndWritesObjects() = withRoot { root, config ->
        config.writeText("[core]\n\tcreateObject = copy # old startup setting\n\tfilemode = false\n")
        assertTrue(GitObjectConfigPolicy.apply(root))
        assertEquals("[core]\n\tcreateObject = rename # old startup setting\n\tfilemode = false\n", config.readText())
        exerciseRepository(root, "rename")
        val before = config.readBytes()
        config.setLastModified(123456000L)
        val timestamp = config.lastModified()
        assertFalse(GitObjectConfigPolicy.apply(root))
        assertArrayEquals(before, config.readBytes())
        assertEquals(timestamp, config.lastModified())
    }

    @Test fun seedsAbsentConfigAndPreservesExplicitModes() {
        for (mode in listOf(null, "link", "rename")) withRoot { root, config ->
            if (mode != null) config.writeText("[core]\ncreateObject = $mode\n")
            assertEquals(mode == null, GitObjectConfigPolicy.apply(root))
            exerciseRepository(root, mode ?: "rename")
            assertFalse(GitObjectConfigPolicy.apply(root))
        }
    }

    @Test fun preservesSectionsCommentsCrLfAndDuplicates() = withRoot { root, config ->
        val original = "# createObject = copy\r\n[core \"custom\"]\r\ncreateObject = copy\r\n[core.legacy]\r\ncreateObject = copy\r\n[url \"https://createObject/copy\"]\r\ninsteadOf = createObject\r\n[CoRe] ; comment\r\n CreateObject = \"copy\" ; keep\r\ncreateObject = link\r\n[core]\r\ncreateObject = co\\\r\npy # continued\r\n[other]\r\nvalue = \"createObject = copy\"\r\n"
        config.writeText(original)
        assertTrue(GitObjectConfigPolicy.apply(root))
        assertEquals(original.replace("\"copy\" ; keep", "rename ; keep").replace("co\\\r\npy # continued", "rename # continued"), config.readText())
        exerciseRepository(root, "rename")
        assertEquals("copy", git(root, "config", "--get", "core.custom.createObject"))
        assertEquals("copy", git(root, "config", "--get", "core.legacy.createObject"))
        assertFalse(GitObjectConfigPolicy.apply(root))
    }

    @Test fun preservesOtherExplicitValuesAndAddsOnlyMissingKey() {
        val explicit = "[core]\ncreateObject = custom # user\n"
        assertEquals(explicit, GitObjectConfigPolicy.migrate(explicit))
        val subsection = "[core \"user\"]\r\ncreateObject = copy"
        assertEquals(subsection + "\r\n[core]\r\n\tcreateObject = rename\r\n", GitObjectConfigPolicy.migrate(subsection))
        val continued = "[other]\nvalue = something\\\n[core]\\\ncreateObject = copy\n"
        assertEquals(continued + "[core]\n\tcreateObject = rename\n", GitObjectConfigPolicy.migrate(continued))
    }
}
