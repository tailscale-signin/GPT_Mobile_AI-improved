package dev.chungjungsoo.gptmobile.data.diagnostics

/** A manual preview is an explicit observation, never an inferred failure or a mutable approved report. */
internal object IssueReportManualCapture {
    private const val MAX_AGE_MILLIS = 10 * 60_000L

    fun descriptionLimit(relatedIssueNumber: Int?): Int = IssueReport.DESCRIPTION_LIMIT - followUpPrefix(relatedIssueNumber).length

    private fun followUpPrefix(relatedIssueNumber: Int?): String = relatedIssueNumber?.takeIf { it > 0 }?.let { "Follow-up to issue #$it.\n\n" }.orEmpty()

    fun preview(
        report: IssueReport,
        include: Boolean,
        area: String?,
        snapshot: IssueReportDiagnostics?,
        events: List<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): IssueReport {
        require(report.source == "manual")
        val originalTime = report.context?.occurredAtMillis ?: nowMillis
        val basic = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = originalTime)
        val context = if (include && area in IssueReportDiagnostics.areas) {
            val selected = snapshot?.validated()?.takeIf {
                it.area == area &&
                    it.captureMode != "NO_CAPTURE" &&
                    it.capturedAtMillis <= nowMillis &&
                    nowMillis - it.capturedAtMillis <= MAX_AGE_MILLIS
            }?.copy(captureMode = "SELECTED_CAPTURE")
            basic.withDiagnostics(
                selected ?: requireNotNull(
                    IssueReportDiagnostics.fromFields(
                        "MANUAL_REPORT",
                        mapOf("diagnostic_area" to area, "diagnostic_capture_mode" to "NO_CAPTURE"),
                        nowMillis
                    )
                )
            )
        } else {
            basic
        }
        return report.copy(context = context, events = if (include) events.toList() else emptyList())
    }

    /** Takes the rendered preview, not a fresh snapshot collected after the user pressed Send. */
    fun submission(preview: IssueReport, title: String, description: String, include: Boolean, relatedIssueNumber: Int? = null): IssueReport {
        require(preview.source == "manual")
        // Retain the metadata already shown in the preview. Constructing a fresh
        // report here would read Android state after review and break pure callers.
        val body = followUpPrefix(relatedIssueNumber) + description
        val context = if (include) {
            preview.context
        } else {
            IssueReportContext.fromFields(
                "MANUAL_REPORT",
                occurredAtMillis = preview.context?.occurredAtMillis ?: System.currentTimeMillis()
            )
        }
        return preview.copy(
            title = diagnosticMessageForStorage(title.trim()).issueReportPrefix(IssueReport.TITLE_LIMIT),
            description = diagnosticMessageForStorage(body.trim()).issueReportPrefix(IssueReport.DESCRIPTION_LIMIT),
            events = if (include) preview.events else emptyList(),
            context = context
        )
    }
}
