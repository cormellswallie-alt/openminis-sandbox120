package com.openminis.app.sandbox

/** Emits text already stripped by the reader, preserving callback order and failures. */
internal class PersistentOutputCallbacks(
    private val output: StringBuilder,
    private val outputCallback: ((String) -> Unit)?,
    private val lineCallback: ((String) -> Unit)?,
) {
    fun emit(visible: String) {
        output.append(visible)
        outputCallback?.invoke(visible)
        lineCallback?.let { feedLines(visible, it) }
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

}
