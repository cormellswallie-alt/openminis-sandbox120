package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject

/** A reverted summary remains recoverable without changing the original messages. */
@Entity(
    tableName = "compact_recovery",
    foreignKeys = [ForeignKey(
        entity = ChatSessionEntity::class,
        parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["session_id"])],
)
data class CompactRecoveryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "marker_json") val markerJson: String,
    val sequence: Long,
)

/** Stable payload also used by the optional recovery section in backups. */
object CompactRecoveryCodec {
    fun encode(marker: CompactMarkerEntity): String = JSONObject().apply {
        put("id", marker.id)
        put("sessionId", marker.sessionId)
        put("summary", marker.summary)
        put("firstKeptSortOrder", marker.firstKeptSortOrder)
        put("compactedCount", marker.compactedCount)
        put("createdAt", marker.createdAt)
        put("uiBoundarySortOrder", marker.uiBoundarySortOrder ?: JSONObject.NULL)
        put("boundaryMessageId", marker.boundaryMessageId ?: JSONObject.NULL)
        put("firstKeptMessageId", marker.firstKeptMessageId ?: JSONObject.NULL)
        put("lastCompactedMessageId", marker.lastCompactedMessageId ?: JSONObject.NULL)
        put("version", marker.version)
    }.toString()

    fun decode(payload: String): CompactMarkerEntity {
        val json = JSONObject(payload)
        fun nullableString(key: String): String? =
            if (json.isNull(key)) null else json.getString(key)
        return CompactMarkerEntity(
            id = json.getString("id"), sessionId = json.getString("sessionId"),
            summary = json.getString("summary"),
            firstKeptSortOrder = json.getInt("firstKeptSortOrder"),
            compactedCount = json.getInt("compactedCount"),
            createdAt = json.getLong("createdAt"),
            uiBoundarySortOrder = if (json.isNull("uiBoundarySortOrder")) null else json.getInt("uiBoundarySortOrder"),
            boundaryMessageId = nullableString("boundaryMessageId"),
            firstKeptMessageId = nullableString("firstKeptMessageId"),
            lastCompactedMessageId = nullableString("lastCompactedMessageId"),
            version = json.optInt("version", 1),
        )
    }
}
