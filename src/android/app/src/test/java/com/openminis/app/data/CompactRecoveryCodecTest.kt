package com.openminis.app.data

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.CompactRecoveryCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactRecoveryCodecTest {
    private fun marker() = CompactMarkerEntity(
        id = "summary-1", sessionId = "session-1", summary = "中文\nRésumé \"original\"",
        firstKeptSortOrder = Int.MAX_VALUE, compactedCount = 120,
        createdAt = 1_790_842_123_456, uiBoundarySortOrder = 42,
        boundaryMessageId = "boundary", firstKeptMessageId = "keep",
        lastCompactedMessageId = "anchor", version = 2,
    )

    @Test fun allMarkerFieldsSurviveRecovery() {
        val original = marker()
        assertEquals(original, CompactRecoveryCodec.decode(CompactRecoveryCodec.encode(original)))
    }

    @Test fun nullableLegacyFieldsStayNull() {
        val original = marker().copy(uiBoundarySortOrder = null, boundaryMessageId = null,
            firstKeptMessageId = null, lastCompactedMessageId = null, version = 1)
        assertEquals(original, CompactRecoveryCodec.decode(CompactRecoveryCodec.encode(original)))
    }

    @Test fun missingVersionUsesLegacySemantics() {
        val json = JSONObject(CompactRecoveryCodec.encode(marker())).apply { remove("version") }
        assertEquals(1, CompactRecoveryCodec.decode(json.toString()).version)
    }

    @Test fun malformedPayloadCannotRestoreAnEmptyMarker() {
        try {
            CompactRecoveryCodec.decode("{}")
            fail("Incomplete recovery must not be applied")
        } catch (_: org.json.JSONException) { }
    }
}
