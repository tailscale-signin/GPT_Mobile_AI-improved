package dev.chungjungsoo.gptmobile.data.github

/**
 * Produces concise Markdown from already approved public progress observations.
 * It never interprets a raw tool result or synthesizes a successful verification.
 */
internal object GitHubRichSummaryPresentation {
    internal data class CheckCount(val label: String, val count: Int)
    internal data class CheckTable(val rows: List<CheckCount>, val source: String, val coverage: String) {
        val headers = listOf("Check state", "Count", "Source", "Captured list", "Observation")
        val observation = "Recorded results; not a new test execution"
    }

    private data class CheckEvidence(val counts: List<CheckCount>, val scope: String?, val complete: Boolean)

    private val countEvidence = Regex(
        """(?i)recorded checks(?: for ([^:\n]{1,96}))?:\s*(\d+) successful,\s*(\d+) skipped,\s*(\d+) in progress,\s*(\d+) failed(?:,\s*(\d+) other completed)?\s*\((complete|incomplete) captured list\)"""
    )
    private val chartRow = Regex("""^\| (Successful|Skipped|In progress|Failed|Other completed) \| (\d{1,9}) \|$""")
    private val failure = Regex("""(?i)\b(?:failed|failure|blocked|needs attention)\b|policy.*unavailable""")
    private val checkWords = Regex("""(?i)\b(?:check|workflow|test|CI|build)\b""")
    private val changeWords = Regex("""(?i)\b(?:patch|commit|write|change request|branch|pull request|PR #)\b""")
    private val sourceWords = Regex("""(?i)\b(?:file|source|read|repository search)\b""")
    private val zeroFailure = Regex("""(?i)\b0\s+(?:failed|blocked)\b""")

    fun isRich(text: String): Boolean = text.startsWith("### ") && text.contains("\n- ")

    /**
     * Stable across a timer tick or changed "next step". New observed facts
     * may change it. A revision counter by itself never creates an insight.
     */
    fun evidenceKey(snapshot: GitHubProgressSnapshot): String =
        factKeys(snapshot).joinToString("\u001f")

    /** Canonical source facts, independent of ordering, clock or narration phrasing. */
    fun factKeys(snapshot: GitHubProgressSnapshot): List<String> =
        observations(snapshot).map(GitHubProgressPresentation::lineKey)

    /** Used only for local history presentation; exclude changing next-step prose. */
    fun publicationKey(markdown: String): String {
        if (!isRich(markdown)) return GitHubProgressPresentation.lineKey(markdown)
        val observations = markdown.lineSequence().map(String::trim).filter { line ->
            when {
                line.startsWith("- **Attention:**") -> true
                line.startsWith("- **Observed:**") -> true
                line.startsWith("- **Evidence:**") -> true
                line.startsWith("- **Source:**") -> true
                line.startsWith("- **Coverage:**") -> true
                line.startsWith("- **Verification:**") -> true
                line.startsWith("- ") && !line.startsWith("- I’ll ") && !line.startsWith("- I'll ") && !line.startsWith("- **Next:**") -> true
                line.startsWith("| ") -> !line.startsWith("| ---") && !line.startsWith("| Check state ")
                else -> false
            }
        }.map(GitHubProgressPresentation::lineKey).toList()
        return observations.takeIf(List<String>::isNotEmpty)?.joinToString("\u001f")
            ?: GitHubProgressPresentation.lineKey(markdown)
    }

    /** Plain, evidence-backed updates vary with the actual file, failure or request. */
    fun render(snapshot: GitHubProgressSnapshot, reported: Set<String> = emptySet()): String {
        val all = observations(snapshot)
        val unseen = all.filter { GitHubProgressPresentation.lineKey(it) !in reported }
        if (unseen.isEmpty()) return ""
        // Keep an unresolved failure visible when new independent evidence arrives,
        // without repeating all the already-reported routine reads.
        val lastProblem = all.lastOrNull { failure.containsMatchIn(it.replace(zeroFailure, "")) }
        val seen = if (lastProblem != null && lastProblem !in unseen) listOf(lastProblem) + unseen else unseen
        val counts = seen.asReversed().firstNotNullOfOrNull(::parseChecks)
        val specific = seen.filterNot { counts != null && parseChecks(it) != null }
        val problem = specific.lastOrNull { failure.containsMatchIn(it.replace(zeroFailure, "")) }
        val latest = specific.lastOrNull()
        val focus = (problem ?: latest)?.let(::observationFocus)
        // Check-table titles remain stable because chart and export parsers verify provenance.
        val headline = when {
            counts != null -> if (counts.complete) "Recorded check results" else "Partial recorded check results"
            problem != null && focus != null -> "$focus needs attention"
            problem != null && checkWords.containsMatchIn(problem) -> "Investigating a check failure"
            problem != null -> "Trouble with ${GitHubProgressPresentation.taskFocus(snapshot.task)}"
            latest != null && focus != null && changeWords.containsMatchIn(latest) -> "Changes involving $focus"
            latest != null && focus != null -> "Reviewing $focus"
            else -> "Investigating ${GitHubProgressPresentation.taskFocus(snapshot.task)}"
        }
        val facts = buildList {
            if (problem != null) add(if (counts == null) describe(problem, focus) else "**Attention:** ${escape(problem)}")
            if (counts != null) {
                counts.scope?.let { add("**Source:** ${escape(it)}") }
                if (!counts.complete) add("**Coverage:** The captured check list is incomplete; missing checks are not measured.")
                val counted = counts.counts.joinToString(", ") { "${it.count} ${it.label.lowercase(java.util.Locale.ROOT)}" }
                add("**Evidence:** These are recorded check results, not a new test execution. $counted (${if (counts.complete) "complete" else "incomplete"} captured list).")
                if (problem == null) specific.lastOrNull()?.let { add(describe(it)) }
            } else {
                specific.takeLast(2).forEach { step ->
                    if (step != problem) add(describe(step, focus))
                }
            }
        }
        val nextAction = snapshot.next.trim().takeIf { (problem != null || counts != null || snapshot.completed == 0) && it.isNotBlank() && !GitHubProgressPresentation.isGenericProgress(it) }?.let {
            "I’ll ${escape(it.replaceFirstChar(Char::lowercase))}"
        }
        val statements = facts.take(4) + listOfNotNull(nextAction)
        if (statements.isEmpty()) return ""
        return buildString {
            append("### ").append(escape(headline)).append('\n')
            statements.forEach { append("- ").append(it).append('\n') }
            counts?.let { observed ->
                append('\n')
                append("| Check state | Count |\n| --- | ---: |\n")
                observed.counts.forEach { append("| ").append(it.label).append(" | ").append(it.count).append(" |\n") }
            }
        }.trim().take(1600)
    }

    /** Shorten receipt boilerplate without upgrading a source read to a verified repair. */
    private fun describe(step: String, currentHeadlineFocus: String? = null): String {
        val read = Regex("""(?i)^The source read for (.+?) returned a result for inspection$""").matchEntire(step)
        if (read != null) return if (observationFocus(step) == currentHeadlineFocus) {
            "The source is available for inspection; no repair has been verified."
        } else {
            "Source read for ${escape(read.groupValues[1])} is available to inspect; no repair has been verified."
        }
        val failedRead = Regex("""(?i)^The source read for (.+?) failed$""").matchEntire(step)
        if (failedRead != null) return "Reading ${escape(failedRead.groupValues[1])} failed; the source still needs recovery."
        return escape(step)
    }

    /** Extract identifiers from bounded approved progress, never from raw result bodies. */
    private fun observationFocus(step: String): String? {
        val forTarget = Regex("""(?i)\bfor (.{1,100}?)(?: returned| failed| started| was skipped| is blocked|;|$)""")
            .find(step)?.groupValues?.get(1)
        val identifier = forTarget ?: Regex("""(?i)\b(?:PR #\d+|Issue #\d+|Job \d+|Run \d+)\b""").find(step)?.value
        return identifier?.let(GitHubProgressPresentation::taskLabel)
            ?.trimEnd('.', ';', ':')?.take(64)?.takeIf(String::isNotBlank)
    }

    /** Only source-owned, complete five-field check observations can create rows. */
    private fun parseChecks(text: String): CheckEvidence? {
        val match = countEvidence.find(text) ?: return null
        val values = (2..6).map { index ->
            match.groupValues[index].ifBlank { "0" }.toIntOrNull()?.takeIf { it in 0..100_000 } ?: return null
        }
        val checkCounts = listOf(
            CheckCount("Successful", values[0]),
            CheckCount("Skipped", values[1]),
            CheckCount("In progress", values[2]),
            CheckCount("Failed", values[3])
        ) + if (values[4] > 0) listOf(CheckCount("Other completed", values[4])) else emptyList()
        return CheckEvidence(checkCounts, match.groupValues[1].takeIf(String::isNotBlank), match.groupValues[7].equals("complete", ignoreCase = true))
    }

    /** Chart rows must match the deterministic table, not arbitrary model text. */
    fun chartData(markdown: String): List<CheckCount> {
        val rows = parseRecordedTable(markdown) ?: return emptyList()
        if (rows.count { it.count > 0 } < 2 || rows.sumOf { it.count } < 3) return emptyList()
        return rows
    }

    fun checkTable(markdown: String): CheckTable? {
        val rows = parseRecordedTable(markdown) ?: return null
        val source = markdown.lineSequence().singleOrNull { it.startsWith("- **Source:** ") }
            ?.removePrefix("- **Source:** ")?.replace(Regex("""\\(.)"""), "$1") ?: "Not recorded"
        val coverage = if (markdown.startsWith("### Partial recorded check results")) "Incomplete" else "Complete"
        return CheckTable(rows, source, coverage)
    }

    fun csv(markdown: String): String? {
        val table = checkTable(markdown) ?: return null

        // A copied table must keep the same scope and limitations as the visible card.
        // Source labels may contain spreadsheet syntax even when Markdown-escaped.
        fun cell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        return table.headers.joinToString(",") + "\r\n" + table.rows.joinToString("\r\n") {
            it.label + "," + it.count + "," + cell(table.source) + "," + table.coverage + "," + table.observation
        } + "\r\n"
    }

    /** Malformed saved or modified tables must not become chart/export data. */
    private fun parseRecordedTable(markdown: String): List<CheckCount>? {
        if (!sourceCountTable(markdown)) return null
        if (markdown.startsWith("### Partial recorded check results") &&
            !markdown.contains("- **Coverage:** The captured check list is incomplete; missing checks are not measured.")
        ) {
            return null
        }
        val lines = markdown.lines().map { it.trim() }.dropLastWhile { it.isBlank() }
        val headerIndex = lines.indexOf("| Check state | Count |")
        if (headerIndex < 0 || lines.getOrNull(headerIndex + 1) != "| --- | ---: |") return null
        val tableLines = lines.drop(headerIndex + 2)
        if (tableLines.size !in 4..5) return null
        val rows = tableLines.map { line ->
            val match = chartRow.matchEntire(line) ?: return null
            val count = match.groupValues[2].toIntOrNull()?.takeIf { it in 0..100_000 } ?: return null
            CheckCount(match.groupValues[1], count)
        }
        val labels = rows.map { it.label }
        val required = listOf("Successful", "Skipped", "In progress", "Failed")
        if (labels != required && labels != required + "Other completed") return null
        return rows
    }

    private fun sourceCountTable(markdown: String): Boolean =
        (markdown.startsWith("### Recorded check results\n") || markdown.startsWith("### Partial recorded check results\n")) &&
            markdown.contains("- **Evidence:** These are recorded check results, not a new test execution.") &&
            markdown.contains("| Check state | Count |\n| --- | ---: |")

    private fun observations(snapshot: GitHubProgressSnapshot): List<String> =
        snapshot.recentSteps.map { raw ->
            GitHubProgressPresentation.preview(raw).lineSequence().firstOrNull().orEmpty().trim().take(320).trimEnd('.', '…')
        }.filter(String::isNotBlank).filterNot(GitHubProgressPresentation::isGenericProgress)
            .distinctBy(GitHubProgressPresentation::lineKey)

    private fun escape(text: String): String = buildString {
        for (c in text.trim().take(220)) {
            val escaped = c == '\\' || c.code == 96 || c == '*' || c == '_' || c == '[' || c == ']' || c == '<' || c == '>' || c == '|'
            // Bound the rendered length too, so escaping cannot truncate the final table.
            if (length + (if (escaped) 2 else 1) > 220) break
            if (escaped) append('\\')
            append(c)
        }
    }.dropLastWhile(Char::isHighSurrogate)
}
