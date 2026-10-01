package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide default for newly created chats. Explicit session/group choices take precedence. */
object ThinkingDefaults {
    private const val STORE = "minis_thinking_defaults"
    private const val KEY = "default_level"
    private val selected = MutableStateFlow(ThinkingLevel.OFF)
    val level = selected.asStateFlow()

    fun load(context: Context): ThinkingLevel {
        val saved = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val value = ThinkingLevel.parseOrNull(saved ?: "") ?: ThinkingLevel.OFF
        selected.value = value
        return value
    }

    fun set(context: Context, value: ThinkingLevel) {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.name).apply()
        selected.value = value
    }
}

internal object ThinkingControlPolicy {
    fun levels(available: List<ThinkingLevel>): List<ThinkingLevel> =
        (listOf(ThinkingLevel.OFF) + available).distinct().sortedBy { it.rank }

    fun resolveDefault(session: ThinkingLevel?, group: ThinkingLevel?, global: ThinkingLevel): ThinkingLevel =
        session ?: group ?: global
}
