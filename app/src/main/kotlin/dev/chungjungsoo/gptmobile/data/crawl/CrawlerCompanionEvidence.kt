package dev.chungjungsoo.gptmobile.data.crawl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Import only committed, hash-checked source chunks; protocol envelopes are never article text. */
internal object CrawlerCompanionEvidence {
    fun normalize(value: JsonElement, url: String): JsonElement {
        val outer = value as? JsonObject ?: return value
        val envelope = outer["structuredContent"] as? JsonObject ?: outer
        if (envelope["schemaVersion"] != JsonPrimitive(1) || envelope["operation"] == null) return value
        require(envelope["error"] == null) { "Crawler companion returned an acquisition failure" }
        val data = envelope["data"] as? JsonObject ?: error("Missing crawler companion data")
        val evidence = data["evidence"] as? JsonObject ?: data
        val chunks = (evidence["chunks"] as? JsonArray).orEmpty().map { it as? JsonObject ?: error("Invalid crawler chunk") }
        require(chunks.isNotEmpty()) { "Crawler companion returned no evidence chunks" }
        val first = chunks.first()["document"] as? JsonObject ?: error("Crawler source metadata missing")
        val source = first["sourceId"]
        val revision = first["documentRevision"]
        val requested = (first["requestedUrl"] as? JsonPrimitive)?.contentOrNull
        require(requested == url) { "Crawler evidence belongs to another source URL" }
        var end = 0
        val body = buildString {
            chunks.forEach { chunk ->
                val document = chunk["document"] as? JsonObject ?: error("Crawler source metadata missing")
                require(document["sourceId"] == source && document["documentRevision"] == revision) { "Crawler response mixed source revisions" }
                val text = (chunk["text"] as? JsonPrimitive)?.content ?: error("Missing crawler text")
                val start = (chunk["startOffset"] as? JsonPrimitive)?.intOrNull
                val next = (chunk["endOffset"] as? JsonPrimitive)?.intOrNull
                require(start == end && next == end + text.codePointCount(0, text.length) && chunk["offsetUnit"] == JsonPrimitive("UNICODE_CODE_POINT_V1")) { "Crawler evidence is incomplete or has invalid offsets" }
                require(chunk["textHash"] == JsonPrimitive(crawlHash(text))) { "Crawler evidence hash mismatch" }
                append(text)
                end = requireNotNull(next)
            }
        }
        val truncated = (evidence["outputTruncated"] as? JsonPrimitive)?.booleanOrNull != false || first["retrievalStatus"] == JsonPrimitive("partial") || (first["storedCharacters"] as? JsonPrimitive)?.intOrNull != end
        return buildJsonObject {
            put("url", first["resolvedUrl"] ?: JsonPrimitive(url))
            put("title", first["title"] ?: JsonPrimitive(""))
            put("content", body)
            put("truncated", truncated)
            put("sourceId", source ?: JsonPrimitive(""))
            put("documentRevision", revision ?: JsonPrimitive(""))
            put(
                "citationMetadata",
                buildJsonObject {
                    put("acquisitionSourceId", source ?: JsonPrimitive(""))
                    put("documentRevision", revision ?: JsonPrimitive(""))
                    first["contentHash"]?.let { put("contentHash", it) }
                }
            )
        }
    }
}
