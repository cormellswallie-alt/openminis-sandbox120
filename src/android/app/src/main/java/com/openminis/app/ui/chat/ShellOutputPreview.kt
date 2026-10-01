package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** Preview only; never used to truncate the executor's final result. */
internal class ShellOutputPreview(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val maxLines: Int = 50,
    private val maxChars: Int = 16 * 1024,
    intervalMillis: Long = 50,
    private val offerUrl: (String) -> Unit = {},
    publish: (String) -> Unit,
) {
    init { require(maxLines > 0 && maxChars > 0 && intervalMillis > 0) }
    private val lock = Any()
    private val ring = CharArray(maxChars)
    private var head = 0
    private var size = 0
    private var newlines = 0
    private var stopped = false
    private var hasLine = false
    private val prefix = "\u001b]1337;MinisOpenURL="
    private val pending = StringBuilder()
    private var inUrl = false
    private var awaitingSt = false
    private var oversized = false
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val updater: Job = scope.launch(dispatcher) {
        for (ignored in signal) {
            if (scope.isActive && isActive) publish(snapshot())
            delay(intervalMillis)
        }
    }

    private fun removeFirst() {
        if (ring[head] == '\n') newlines--
        head = (head + 1) % maxChars
        size--
    }

    private fun visible(c: Char) {
        if (size == maxChars) removeFirst()
        ring[(head + size) % maxChars] = c
        size++
        if (c == '\n') newlines++
        while (newlines >= maxLines) removeFirst()
    }

    private fun releasePending() {
        pending.forEach { visible(it) }
        pending.setLength(0)
    }

    private fun consume(c: Char) {
        if (inUrl) {
            if (awaitingSt) {
                if (c == '\\') {
                    completeMarker()
                    return
                }
                // Malformed terminator: retain original data in the bounded preview.
                if (!oversized) { visiblePrefixAndUrl(); visible('\u001b') }
                resetMarker()
                consume(c)
                return
            }
            when (c) {
                '\u0007' -> completeMarker()
                '\u001b' -> awaitingSt = true
                else -> if (!oversized) {
                    if (pending.length < maxChars) pending.append(c)
                    else { pending.setLength(0); oversized = true }
                }
            }
            return
        }
        if (c == prefix[pending.length]) {
            pending.append(c)
            if (pending.length == prefix.length) {
                pending.setLength(0)
                inUrl = true
            }
        } else {
            releasePending()
            if (c == prefix[0]) pending.append(c) else visible(c)
        }
    }

    private fun visiblePrefixAndUrl() {
        prefix.forEach { visible(it) }
        releasePending()
    }

    private fun resetMarker() {
        pending.setLength(0)
        inUrl = false
        awaitingSt = false
        oversized = false
    }

    private fun completeMarker() {
        if (!oversized && pending.isNotEmpty()) offerUrl(pending.toString())
        resetMarker()
    }

    /** Reader only acquires a bounded-state lock; it never waits for Main. */
    fun appendChunk(chunk: String) = synchronized(lock) {
        if (stopped) return@synchronized
        chunk.forEach { consume(it) }
        if (size > 0) signal.trySend(Unit)
    }

    /** Compatibility for line producers and the old-preview benchmark. */
    fun onLine(line: String) = synchronized(lock) {
        if (stopped) return@synchronized
        if (hasLine) consume('\n')
        hasLine = true
        line.forEach { consume(it) }
        signal.trySend(Unit)
    }

    /** Flush incomplete escape data at EOF; complete markers were already offered. */
    fun flush() = synchronized(lock) {
        if (stopped) return@synchronized
        if (inUrl) {
            if (!oversized) {
                visiblePrefixAndUrl()
                if (awaitingSt) visible('\u001b')
            }
            resetMarker()
        } else releasePending()
        if (size > 0) signal.trySend(Unit)
    }

    internal fun snapshot(): String = synchronized(lock) {
        buildString(size) { repeat(size) { append(ring[(head + it) % maxChars]) } }
    }

    /** Must finish before the caller publishes its complete final result. */
    suspend fun stop() {
        synchronized(lock) { stopped = true; signal.close() }
        updater.cancelAndJoin()
    }
}

/** Evaluated on Main alongside the target list transaction; never gates unrelated helpers. */
internal object ShellPreviewPublishPolicy {
    fun shouldFlush(
        structuralChange: Boolean,
        elapsedMillis: Long,
        throttleMillis: Long,
        newlineFlush: Boolean,
        toolPreviewUpdate: Boolean = false,
    ): Boolean = structuralChange || elapsedMillis >= throttleMillis || newlineFlush || toolPreviewUpdate

    fun mayPublish(
        parentActive: Boolean,
        sameSession: Boolean,
        messageStreaming: Boolean,
        blockRunning: Boolean,
    ): Boolean = parentActive && sameSession && messageStreaming && blockRunning
}
