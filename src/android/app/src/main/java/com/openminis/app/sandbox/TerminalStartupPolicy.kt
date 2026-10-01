package com.openminis.app.sandbox

import java.io.File
import kotlinx.coroutines.delay

/** Startup configuration never writes commands into the user's input stream. */
internal object TerminalStartupPolicy {
    fun workingDirectory(sessionId: String?): String =
        if (sessionId == null) "/root" else "/var/minis"

    fun workingDirectoryArgs(rootfs: File, sessionId: String?): List<String> {
        val cwd = workingDirectory(sessionId)
        val directory = File(rootfs, cwd.removePrefix("/"))
        if (!directory.isDirectory) directory.mkdirs()
        check(directory.isDirectory) { "Terminal working directory is unavailable: $cwd" }
        return listOf("-w", cwd)
    }

    val shellArgs: List<String> = listOf("/bin/sh", "-l", "-i")

    /** Run in a sibling job: this delay must never gate PTY reads or waitpid. */
    suspend fun emitBanner(isCurrent: () -> Boolean, emit: suspend () -> Unit) {
        delay(300)
        if (isCurrent()) emit()
    }
}
