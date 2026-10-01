package com.openminis.app.provider.openai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Hosted search annotations belong to an output message, not a local function call. */
internal class WebSearchCitations {
    private data class Citation(val url: String, val title: String, val start: Int, val end: Int)
    private val annotations = linkedMapOf<String, Citation>()

    val hasPending: Boolean get() = annotations.isNotEmpty()

    fun resetMessage() = annotations.clear()

    fun add(annotation: JSONObject?) {
        if (annotation?.optString("type") != "url_citation") return
        val value = annotation.optJSONObject("url_citation") ?: annotation
        val url = value.optString("url", "").toHttpUrlOrNull()?.toString() ?: return
        val title = value.optString("title", "").ifBlank { url }
        val start = value.optInt("start_index", -1)
        val end = value.optInt("end_index", -1)
        annotations["$start:$end:$url"] = Citation(url, title, start, end)
    }

    fun addMessage(item: JSONObject?) {
        if (item?.optString("type") != "message") return
        val content = item.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
            val annotations = content.optJSONObject(i)?.optJSONArray("annotations") ?: continue
            for (j in 0 until annotations.length()) add(annotations.optJSONObject(j))
        }
    }

    /** Idempotent across annotation, item-done and response-completed events. */
    fun renderInline(text: String): String {
        var result = text
        var boundary = text.length
        val displayed = mutableSetOf<String>()
        for (citation in annotations.values.sortedByDescending { it.start }) {
            if (citation.start < 0 || citation.end <= citation.start ||
                citation.end > boundary || citation.end > text.length
            ) continue
            val original = text.substring(citation.start, citation.end)
            val label = if ('\uE200' in original || '\uE201' in original) citation.title else original
            result = result.substring(0, citation.start) + link(label, citation.url) + result.substring(citation.end)
            displayed.add(citation.url)
            boundary = citation.start
        }
        val remaining = annotations.values.distinctBy { it.url }.filter { it.url !in displayed }
        if (remaining.isNotEmpty()) {
            result += "\n\n" + remaining.joinToString("\n") { "- " + link(it.title, it.url) } + "\n"
        }
        return result
    }

    private fun link(title: String, url: String): String {
        val label = title.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
            .replace("\n", " ").replace("\r", " ")
        val destination = url.replace("(", "%28").replace(")", "%29")
        return "[$label]($destination)"
    }
}
