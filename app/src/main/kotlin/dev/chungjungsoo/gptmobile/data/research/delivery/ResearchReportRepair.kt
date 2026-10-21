package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A correction may replace only rejected paragraph bytes; valid paragraphs retain their identity. */
internal object ResearchReportRepair {
    data class Block(val id: String, val start: Int, val end: Int, val text: String, val digest: String, val issues: List<String>)
    private val globalIssues = listOf("MISSING_SECTION:", "OMITTED_LEADING_STUDY:", "OMITTED_UNREAD_LEAD:", "DIRECT_REFERENCE_MISSING:", "PARTIAL_COVERAGE_NOT_DISCLOSED", "REVIEW_STATUS_NOT_DISCLOSED", "QUESTION_EVIDENCE_NOT_CITED:", "COUNTEREVIDENCE_NOT_DISCLOSED")
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
            val issues = ResearchDeliveryValidator.validate(body, packet).issues.filter { issue -> globalIssues.none(issue::startsWith) }
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
    fun apply(text: String, reply: String, packet: ResearchDeliveryPacket): String? = runCatching {
        require(reply.length <= 100_000)
        val root = Json.parseToJsonElement(reply).jsonObject
        require(root.keys.all { it in setOf("replacements", "append") })
        val changes = root["replacements"] as? JsonArray ?: error("Missing replacement list")
        val available = blocks(text, packet).filter { it.issues.isNotEmpty() }.associateBy { it.id }
        require(changes.size <= available.size)
        val seen = hashSetOf<String>()
        val replacements = changes.map { value ->
            val row = value.jsonObject
            require(row.keys == setOf("block_id", "previous_sha256", "text"))
            fun string(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid replacement field")
            val id = string("block_id")
            require(seen.add(id))
            val block = requireNotNull(available[id])
            require(string("previous_sha256") == block.digest)
            val replacement = string("text")
            require(blocks(replacement, packet).all { it.issues.isEmpty() })
            block to replacement
        }.sortedBy { it.first.start }
        val appendText = (root["append"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid append")
        val result = buildString {
            var cursor = 0
            replacements.forEach { (block, replacement) ->
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
        require(result.length <= 100_000)
        result
    }.getOrNull()
}
