package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The observed transport is independent of where inference actually executed. */
@Serializable
enum class ResponseTransport { UNKNOWN, DIRECT, GATEWAY }

@Serializable
data class ResponseSource(
    val transport: ResponseTransport = ResponseTransport.UNKNOWN,
    val executionLocation: String? = null,
    val profileUid: String? = null,
    val modelId: String? = null
)

class ResponseSourceConverter {
    @TypeConverter
    fun encode(value: ResponseSource?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun decode(value: String?): ResponseSource? = value?.let { json.decodeFromString<ResponseSource>(it) }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Unknown or mixed historical text must not inherit a currently selected profile's route. */
fun List<AssistantTimelineItem>.uniformResponseSource(): ResponseSource? {
    val text = filter { it.type == AssistantTimelineItemType.TEXT && it.content.isNotEmpty() }
    if (text.isEmpty()) return null
    val first = text.first().responseSource ?: return null
    return first.takeIf { text.all { item -> item.responseSource == first } }
}

internal data class ResponseSourceRange(val start: Int, val end: Int, val source: ResponseSource?)

/** Only exact timeline projections can attribute individual committed characters. */
internal fun responseSourceRanges(text: String, timeline: List<AssistantTimelineItem>): List<ResponseSourceRange> {
    val items = timeline.filter { it.type == AssistantTimelineItemType.TEXT && it.content.isNotEmpty() }
    var offset = 0
    val ranges = items.map { item ->
        val start = offset
        if (!text.regionMatches(offset, item.content, 0, item.content.length)) return listOf(ResponseSourceRange(0, text.length, null))
        offset += item.content.length
        ResponseSourceRange(start, offset, item.responseSource)
    }
    return ranges.takeIf { offset == text.length } ?: listOf(ResponseSourceRange(0, text.length, null))
}
