package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.GroupedAppLog
import dev.chungjungsoo.gptmobile.data.diagnostics.classifyDiagnostic

/** Computed on Default; Compose only displays the retained projection. */
internal data class AppLogRow(
    val key: String,
    val group: GroupedAppLog,
    val preview: String,
    val severity: String,
    val hasDetails: Boolean
)

internal data class AppLogProjection(
    val totalEvents: Int = 0,
    val warningEvents: Int = 0,
    val errorEvents: Int = 0,
    val tags: List<String> = listOf("All tags"),
    val rows: List<AppLogRow> = emptyList(),
    val visibleEvents: Int = 0,
    val visibleIssues: Int = 0,
    val matchingRows: Int = 0,
    val pageIndex: Int = 0,
    val hasOlderRows: Boolean = false
)

/** Cache only the visible page. Immutable unchanged groups do not reformat or reparse their previews. */
internal class AppLogProjector {
    private var rows = java.util.IdentityHashMap<GroupedAppLog, AppLogRow>()

    fun project(entries: List<AppLogEntry>, groups: List<GroupedAppLog>, errorsOnly: Boolean, selectedTag: String, query: String, pageIndex: Int = 0): AppLogProjection {
        val visibleRows = java.util.IdentityHashMap<GroupedAppLog, AppLogRow>()
        val projection = projectAppLogs(entries, groups, errorsOnly, selectedTag, query, pageIndex) { group ->
            (rows[group] ?: projectAppLogRow(group)).also { visibleRows[group] = it }
        }
        rows = visibleRows
        return projection
    }
}

internal fun projectAppLogs(entries: List<AppLogEntry>, groups: List<GroupedAppLog>, errorsOnly: Boolean, selectedTag: String, query: String, pageIndex: Int = 0): AppLogProjection =
    projectAppLogs(entries, groups, errorsOnly, selectedTag, query, pageIndex, ::projectAppLogRow)

private fun projectAppLogs(entries: List<AppLogEntry>, groups: List<GroupedAppLog>, errorsOnly: Boolean, selectedTag: String, query: String, pageIndex: Int, row: (GroupedAppLog) -> AppLogRow): AppLogProjection {
    val matching = groups.filter { group ->
        (!errorsOnly || group.incidentIds.isNotEmpty()) &&
            group.rawDetails.any { entry ->
                (selectedTag == "All tags" || entry.tag == selectedTag) &&
                    (entry.tag.contains(query, ignoreCase = true) || entry.message.contains(query, ignoreCase = true))
            }
    }
    val page = pageIndex.coerceIn(0, ((matching.size - 1) / 100).coerceAtLeast(0))
    val visible = matching.asReversed().drop(page * 100).take(100)
    return AppLogProjection(
        totalEvents = entries.size,
        warningEvents = entries.count { it.level == "W" || it.level == "E" || it.level == "F" },
        errorEvents = entries.count { it.level == "E" || it.level == "F" },
        tags = listOf("All tags") + entries.map { it.tag }.distinct().sorted(),
        rows = visible.map(row),
        visibleEvents = visible.sumOf { it.repetitions },
        visibleIssues = visible.flatMap { it.unresolvedIncidentIds }.distinct().size,
        matchingRows = matching.size,
        pageIndex = page,
        hasOlderRows = (page + 1) * 100 < matching.size
    )
}

private fun projectAppLogRow(group: GroupedAppLog): AppLogRow {
    val line = group.entry.line()
    val recovered = group.incidentIds.isNotEmpty() && group.unresolvedIncidentIds.isEmpty()
    return AppLogRow(
        // The representative may become an error later; the group's
        // original event remains its identity as new records arrive.
        key = group.rawDetails.firstOrNull()?.eventId ?: group.entry.eventId,
        group = group,
        preview = line.take(2000) +
            (if (line.length > 2000) "\n… Open raw details for the full event." else "") +
            (if (group.repetitions > 1) "\n× ${group.repetitions} · last ${java.time.Instant.ofEpochMilli(group.lastTime)}" else "") +
            (if (recovered) "\nRecovered; original events retained." else ""),
        severity = if (recovered) "info" else classifyDiagnostic(group.entry.level, group.entry.tag, group.entry.message).severity,
        hasDetails = group.repetitions > 1 || line.length > 2000
    )
}
