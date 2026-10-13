package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.truncateUtf8
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class DelegationSource(
    val id: String,
    val url: String,
    val title: String,
    val snippet: String = "",
    val text: String = "",
    val pageRead: Boolean = false,
    val excerpted: Boolean = false,
    val depth: Int = 0,
    val evidenceType: String? = null
)

internal fun parseDelegationObject(text: String): JsonObject? = runCatching {
    Json.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) as? JsonObject
}.getOrNull()

internal fun publicResearchUrl(value: String): String? = runCatching {
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    value.trim().substringBefore('#')
}.getOrNull()

/** A URL's actual network safety is checked by the page tool on every request/redirect. */
internal fun researchLinks(text: String, base: String? = null): List<String> {
    val urls = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ';', ']') }
    val hrefs = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).map { match ->
        val href = match.groupValues[1].replace("&amp;", "&")
        runCatching { base?.let { URI(it).resolve(href).toString() } ?: href }.getOrDefault("")
    }
    return (hrefs + urls).mapNotNull(::publicResearchUrl).distinctBy(::canonicalSearchUrl).take(64).toList()
}

/** Keep exact passages, favoring query terms, rather than sending only the beginning of a long page. */
internal fun relevantEvidence(text: String, task: String, maxBytes: Int): String {
    if (text.toByteArray().size <= maxBytes) return text
    if (maxBytes <= 0) return ""
    val terms = Regex("[\\p{L}\\p{N}]{3,}").findAll(task.lowercase()).map { it.value }.toSet()
    val passages = buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 600, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }
    val ranked = passages.withIndex().sortedWith(
        compareByDescending<IndexedValue<String>> { passage -> terms.count { it in passage.value.lowercase() } }
            .thenBy { it.index }
    )
    val selected = mutableMapOf<Int, String>()
    var remaining = maxBytes
    // A short lead retains the document's subject even when later passages rank higher.
    val lead = truncateUtf8(passages.firstOrNull().orEmpty(), minOf(remaining / 4, 300))
    selected[0] = lead
    remaining -= lead.toByteArray().size + 12
    for ((index, passage) in ranked) {
        if (remaining <= 0) continue
        val excerpt = truncateUtf8(if (index == 0) passage.removePrefix(lead) else passage, remaining)
        selected[index] = if (index == 0) lead + excerpt else excerpt
        remaining -= excerpt.toByteArray().size + 12
    }
    return truncateUtf8(selected.toSortedMap().values.joinToString("\n[…]\n"), maxBytes)
}

/** JSON framing keeps supplied tasks and retrieved evidence distinct from worker instructions. */
internal fun delegationPrompt(instruction: String, task: String, evidence: String, maxBytes: Int): String {
    var taskText = truncateUtf8(task, (maxBytes / 4).coerceAtLeast(100))
    var data = relevantEvidence(evidence, task, (maxBytes - instruction.toByteArray().size - taskText.toByteArray().size - 100).coerceAtLeast(0))
    fun render() = instruction + "\n" + buildJsonObject {
        put("task", taskText)
        put("untrusted_evidence", data)
    }
    while (render().toByteArray().size > maxBytes && (data.isNotEmpty() || taskText.isNotEmpty())) {
        if (data.isNotEmpty()) data = truncateUtf8(data, (data.toByteArray().size * 3 / 4)) else taskText = truncateUtf8(taskText, taskText.toByteArray().size / 2)
    }
    return render()
}

/** Only observed source URLs are emitted; never trust model-generated citations or URLs. */
internal fun delegationHandoff(summary: String, sources: List<DelegationSource>, limitations: List<String>, tokenBudget: Int): String {
    val maxBytes = tokenBudget.coerceIn(128, 4096) * 3
    val known = sources.associateBy { it.id }
    val sourceRows = linkedMapOf<String, JsonObject>()
    val idPattern = Regex("\\bS(?:[a-f0-9]{16}|\\d+)\\b")
    val findings = mutableListOf<String>()
    val notes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    fun warningOnly(note: String) = note == "Some search engines were unavailable." ||
        note.startsWith("The brief prioritizes read pages") ||
        note.startsWith("[") &&
        note.contains("could not be read by enabled page readers") ||
        note.startsWith("Search ") &&
        (note.contains("another enabled search provider was attempted") || note.contains("remaining planned queries were still attempted"))
    val important = limitations.distinct().filterNot(::warningOnly).sortedByDescending { "review" in it.lowercase() || "verif" in it.lowercase() }
    val warningNotes = limitations.distinct().filter(::warningOnly)
    val review = if (limitations.any { "review" in it.lowercase() && ("unavailable" in it.lowercase() || "unverified" in it.lowercase()) }) "unavailable" else "see retained report; no independent pass implied"
    // Blank-line-separated findings keep each claim with its exact passage. Never cut a quote.
    val blocks = summary.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    var omittedFindings = blocks.size
    fun render() = buildJsonObject {
        put("kind", "local_evidence")
        put("partial", important.isNotEmpty() || omittedFindings > 0)
        put("review", review)
        put("findings", findings.joinToString("\n"))
        put("sources", JsonArray(sourceRows.values.toList()))
        put("limitations", JsonArray(notes.map(::JsonPrimitive)))
        put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
        put(
            "omitted",
            buildJsonObject {
                put("findings", omittedFindings)
                put("sources", sources.size - sourceRows.size)
                put("limitations", important.size - notes.size)
            }
        )
    }.toString()
    for (note in important) {
        notes += note
        if (render().toByteArray().size > maxBytes) notes.removeAt(notes.lastIndex)
    }
    for (block in blocks) {
        val ids = idPattern.findAll(block).map { it.value }.toSet()
        if (ids.any { it !in known }) continue
        val safe = Regex("https?://[^\\s<>\"')]+", RegexOption.IGNORE_CASE).replace(block) { match ->
            sources.firstOrNull { canonicalSearchUrl(it.url) == canonicalSearchUrl(match.value.trimEnd('.', ',', ';')) }?.let { "[${it.id}]" } ?: "[unverified URL omitted]"
        }
        val references = idPattern.findAll(safe).map { it.value }.toSet()
        val added = references - sourceRows.keys
        added.forEach { id ->
            val source = known.getValue(id)
            sourceRows[id] = buildJsonObject {
                put("id", id)
                put("url", source.url)
                put("title", truncateUtf8(source.title, 120))
                put("evidence", source.evidenceType ?: if (source.pageRead) if (source.excerpted) "page excerpt" else "page read" else "search snippet")
            }
        }
        findings += safe
        omittedFindings--
        if (render().toByteArray().size > maxBytes) {
            findings.removeAt(findings.lastIndex)
            omittedFindings++
            added.forEach(sourceRows::remove)
        }
    }
    for (warning in warningNotes) {
        warnings += warning
        if (render().toByteArray().size > maxBytes) warnings.removeAt(warnings.lastIndex)
    }
    return render()
}

/** Keep literal identifiers and numeric facts alongside a lossy model summary. */
internal fun preserveDelegationFacts(evidence: String, summary: String, maxCharacters: Int): String {
    val anchors = Regex("""https?://[^\s<>"]+|\b[A-Za-z][A-Za-z0-9]*[-_][A-Za-z0-9_-]+\b|\bS\d+\b|\b\d+(?:[.,:/-]\d+)*%?\b""")
        .findAll(evidence).map { it.value }.distinct().filter { it !in summary }.toList()
    if (anchors.isEmpty()) return summary
    val limit = maxCharacters.coerceAtLeast(32)
    val retained = anchors.joinToString(" · ")
    // If preserving the literals itself exceeds the output budget, keep original
    // evidence rather than returning a summary that falsely appears complete.
    val suffix = "Exact evidence values: $retained\n"
    if (suffix.length >= limit) return evidence
    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Delegation", "COMPACTION_FACTS_RESTORED · identifiers=${anchors.size}", "W")
    return suffix + summary.take(limit - suffix.length)
}
