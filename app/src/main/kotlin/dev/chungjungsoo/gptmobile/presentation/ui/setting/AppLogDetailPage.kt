package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry

internal data class AppLogDetailCursor(val eventIndex: Int = 0, val characterOffset: Int = 0)
internal data class AppLogDetailRow(val key: String, val recordNumber: Int, val continued: Boolean, val text: String)
internal data class AppLogDetailPage(val rows: List<AppLogDetailRow> = emptyList(), val next: AppLogDetailCursor? = null, val totalRecords: Int = 0)

/** A page replaces the previous page; opening details cannot grow one Compose item without bound. */
internal fun pageAppLogDetails(entries: List<AppLogEntry>, cursor: AppLogDetailCursor = AppLogDetailCursor(), characterLimit: Int = 16_384, rowLimit: Int = 10): AppLogDetailPage {
    require(characterLimit in 2..16_384 && rowLimit in 1..10)
    require(cursor.eventIndex >= 0 && cursor.characterOffset >= 0)
    var index = cursor.eventIndex
    var offset = cursor.characterOffset
    var remaining = characterLimit
    val rows = mutableListOf<AppLogDetailRow>()
    while (index < entries.size && remaining > 1 && rows.size < rowLimit) {
        val entry = entries[index]
        // Formatting/redaction happens on the caller's Default dispatcher, only
        // for this page. The full retained log stays available through export.
        val line = entry.line()
        if (offset >= line.length) {
            index++
            offset = 0
            continue
        }
        var end = minOf(line.length, offset + minOf(remaining, 4096))
        if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
        val text = line.substring(offset, end)
        rows += AppLogDetailRow("${entry.eventId}:$offset", index + 1, offset > 0, text)
        remaining -= text.length
        offset = end
        if (offset == line.length) {
            index++
            offset = 0
        }
    }
    return AppLogDetailPage(rows, if (index < entries.size) AppLogDetailCursor(index, offset) else null, entries.size)
}
