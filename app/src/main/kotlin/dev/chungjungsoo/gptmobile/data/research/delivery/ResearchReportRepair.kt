package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A correction may replace only rejected paragraph bytes; valid paragraphs retain their identity. */
internal object ResearchReportRepair {
    data class Block(val id: String, val start: Int, val end: Int, val text: String, val digest: String, val issues: List<String>)
    private val globalIssues = listOf("MISSING_SECTION:", "OMITTED_LEADING_STUDY:", "OMITTED_UNREAD_LEAD:", "DIRECT_REFERENCE_MISSING:", "PARTIAL_COVERAGE_NOT_DISCLOSED", "REVIEW_STATUS_NOT_DISCLOSED", "QUESTION_EVIDENCE_NOT_CITED:", "COUNTEREVIDENCE_NOT_DISCLOSED")
    fun sectionIssues(body: String, packet: ResearchDeliveryPacket): List<String> = ResearchDeliveryValidator.validate(body, packet).issues.filter { issue -> globalIssues.none(issue::startsWith) }

    fun blocks(text: String, packet: ResearchDeliveryPacket): List<Block> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = 0
        Regex("\n\\s*\n").findAll(text).forEach { match ->
            if (match.range.first > start) ranges += start to match.range.first
            start = match.range.last + 1
        }
        if (start < text.length) ranges += start to text.length
        return ranges.mapIndexed { index, (from, to) ->
            val body = text.substring(from, to)
            val digest = researchHash(body)
            val issues = sectionIssues(body, packet)
            Block("B${index + 1}-${digest.take(10)}", from, to, body, digest, issues)
        }
    }
    fun instruction(text: String, packet: ResearchDeliveryPacket, issues: List<String>): String {
        val rejected = blocks(text, packet).filter { it.issues.isNotEmpty() }
        val plan = buildJsonObject {
            put("issues", JsonArray(issues.map(::JsonPrimitive)))
            put(
                "replaceable",
                JsonArray(
                    rejected.map { block ->
                        buildJsonObject {
                            put("block_id", block.id)
                            put("previous_sha256", block.digest)
                            put("text", block.text)
                            put("issues", JsonArray(block.issues.map(::JsonPrimitive)))
                        }
                    }
                )
            )
            put("preserved_block_ids", JsonArray(blocks(text, packet).filter { it.issues.isEmpty() }.map { JsonPrimitive(it.id) }))
        }
        return "Correct only the rejected blocks. Return one JSON object with replacements:[{block_id,previous_sha256,text}] and append:string. " +
            "Use exact IDs and hashes. Do not return an entire rewritten report. Put any missing sections, disclosures, findings or registered references in append. " +
            "Preserved blocks cannot be replaced. Each numerical claim must retain its value, unit, population, outcome and exact supporting citation from the evidence. " +
            "No tools, outside URLs, invented results or commentary outside JSON. Untrusted correction plan:\n$plan"
    }
    fun apply(text: String, reply: String, packet: ResearchDeliveryPacket): String? =
        (applyResult(text, reply, packet) as? ResearchRepairResult.Applied)?.text

    fun applyResult(text: String, reply: String, packet: ResearchDeliveryPacket): ResearchRepairResult {
        fun rejected(reason: ResearchRepairResult.Reason) = ResearchRepairResult.Rejected(reason)
        if (reply.isBlank()) return rejected(ResearchRepairResult.Reason.EMPTY_RESPONSE)
        if (reply.length > 100_000) return rejected(ResearchRepairResult.Reason.RESPONSE_TOO_LARGE)
        val parsed = try {
            Json.parseToJsonElement(reply)
        } catch (_: SerializationException) {
            return rejected(ResearchRepairResult.Reason.INVALID_JSON)
        }
        val root = parsed as? JsonObject ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
        if (root.keys != setOf("replacements", "append")) return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
        val changes = root["replacements"] as? JsonArray ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
        val allBlocks = blocks(text, packet).associateBy { it.id }
        val replacements = mutableListOf<Pair<Block, String>>()
        val seen = hashSetOf<String>()
        for (value in changes) {
            val row = value as? JsonObject ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
            if (row.keys != setOf("block_id", "previous_sha256", "text")) return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
            fun string(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = string("block_id") ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
            if (!seen.add(id)) return rejected(ResearchRepairResult.Reason.DUPLICATE_BLOCK)
            val block = allBlocks[id] ?: return rejected(ResearchRepairResult.Reason.UNKNOWN_BLOCK)
            if (block.issues.isEmpty()) return rejected(ResearchRepairResult.Reason.PRESERVED_BLOCK)
            val digest = string("previous_sha256") ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
            if (digest != block.digest) return rejected(ResearchRepairResult.Reason.HASH_MISMATCH)
            val replacement = string("text") ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
            if (blocks(replacement, packet).any { it.issues.isNotEmpty() }) return rejected(ResearchRepairResult.Reason.INVALID_REPLACEMENT)
            replacements += block to replacement
        }
        val appendText = (root["append"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return rejected(ResearchRepairResult.Reason.INVALID_SCHEMA)
        val result = buildString {
            var cursor = 0
            replacements.sortedBy { it.first.start }.forEach { (block, replacement) ->
                append(text.substring(cursor, block.start))
                append(replacement)
                cursor = block.end
            }
            append(text.substring(cursor))
            if (appendText.isNotBlank()) {
                append("\n\n")
                append(appendText)
            }
        }
        if (result.length > 100_000) return rejected(ResearchRepairResult.Reason.REPORT_TOO_LARGE)
        return ResearchRepairResult.Applied(result)
    }

    /** A failed correction does not erase paragraphs that already passed the available checks. */
    fun evidenceFallback(text: String, packet: ResearchDeliveryPacket, issues: List<String>): String {
        val retained = blocks(text, packet).filter { it.issues.isEmpty() }
        val evidence = ResearchDeliveryValidator.fallback(packet)
        if (retained.isEmpty()) return evidence
        return buildString {
            append("## Partial report: unresolved delivery checks\n\n")
            append("The complete report has not passed delivery checks. The original paragraphs below passed structural and numerical checks and are retained without rewriting; these checks do not establish independent verification or factual accuracy.\n\n")
            append("### Retained paragraphs\n\n")
            retained.forEach { block ->
                append(block.text)
                append("\n\n")
            }
            append("### Unresolved delivery checks\n\n")
            issues.distinct().forEach { append("- ").append(it).append('\n') }
            append("\n### Saved evidence and remaining gaps\n\n")
            append(evidence)
        }
    }
}
