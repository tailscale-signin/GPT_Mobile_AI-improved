package dev.chungjungsoo.gptmobile.data.github

/**
 * Produces concise Markdown from already approved public progress observations.
 * It never interprets a raw tool result or synthesizes a successful verification.
 */
internal object GitHubRichSummaryPresentation {
    internal data class CheckCount(val label: String, val count: Int)
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

    fun isRich(text: String): Boolean = text.startsWith("### ") && text.contains("\n- **")

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
                line.startsWith("| ") -> !line.startsWith("| ---") && !line.startsWith("| Check state ")
                else -> false
            }
        }.map(GitHubProgressPresentation::lineKey).toList()
        return observations.takeIf(List<String>::isNotEmpty)?.joinToString("\u001f")
            ?: GitHubProgressPresentation.lineKey(markdown)
    }

    fun render(snapshot: GitHubProgressSnapshot): String {
        val seen = observations(snapshot)
        if (seen.isEmpty()) return ""
        val counts = seen.asReversed().firstNotNullOfOrNull(::parseChecks)
        val specific = seen.filterNot { counts != null && parseChecks(it) != null }
        val problem = specific.lastOrNull { failure.containsMatchIn(it.replace(zeroFailure, "")) }
        val latest = specific.lastOrNull()
        val headline = when {
            counts != null -> if (counts.complete) "Recorded check results" else "Partial recorded check results"
            problem != null && checkWords.containsMatchIn(problem) -> "Validation needs attention"
            problem != null -> "Repository blocker identified"
            latest != null && changeWords.containsMatchIn(latest) -> "Repository changes"
            latest != null && sourceWords.containsMatchIn(latest) -> "Source inspection"
            else -> "Repository findings"
        }
        val facts = buildList {
            if (problem != null) add("**Attention:** ${escape(problem)}")
            if (counts != null) {
                counts.scope?.let { add("**Source:** ${escape(it)}") }
                if (!counts.complete) add("**Coverage:** The captured check list is incomplete; missing checks are not measured.")
                add("**Evidence:** These are recorded check results, not a new test execution.")
                if (problem == null) specific.lastOrNull()?.let { add("**Observed:** ${escape(it)}") }
            } else {
                specific.takeLast(2).forEach { step ->
                    if (step != problem) add("**Observed:** ${escape(step)}")
                }
                if (specific.size == 1 && problem == null && sourceWords.containsMatchIn(specific.single())) {
                    add("**Verification:** Inspecting the source does not establish that the requested fix or tests passed.")
                }
            }
        }
        val nextAction = snapshot.next.takeIf(String::isNotBlank)?.let {
            "**Next:** ${escape(it.replaceFirstChar(Char::lowercase))}"
        }
        val statements = facts.take(4) + listOfNotNull(nextAction)
        if (statements.isEmpty()) return ""
        return buildString {
            append("### ").append(headline).append('\n')
            statements.forEach { append("- ").append(it).append('\n') }
            counts?.let { observed ->
                append('\n')
                append("| Check state | Count |\n| --- | ---: |\n")
                observed.counts.forEach { append("| ").append(it.label).append(" | ").append(it.count).append(" |\n") }
            }
        }.trim().take(1600)
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

    fun csv(markdown: String): String? {
        val rows = parseRecordedTable(markdown) ?: return null
        // Fixed source-owned labels and bounded integers cannot become CSV formulas.
        return "Check state,Count\r\n" + rows.joinToString("\r\n") { it.label + "," + it.count } + "\r\n"
    }

    /** Malformed saved or modified tables must not become chart/export data. */
    private fun parseRecordedTable(markdown: String): List<CheckCount>? {
        if (!sourceCountTable(markdown)) return null
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
        text.trim().take(220).forEach { c ->
            if (c == '\\' || c.code == 96 || c == '*' || c == '_' || c == '[' || c == ']' || c == '<' || c == '>' || c == '|') append('\\')
            append(c)
        }
    }
}
