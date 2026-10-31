package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Bounded verdict arrays leave room for visible JSON after any permitted reasoning. */
internal object GitHubReviewSchema {
    val format = Json.parseToJsonElement("""{"type":"json_schema","json_schema":{"name":"github_review","strict":true,"schema":{"type":"object","properties":{"approved":{"type":"boolean"},"reviewed_ids":{"type":"array","maxItems":16,"items":{"type":"string","maxLength":160}},"issues":{"type":"array","maxItems":8,"items":{"type":"string","maxLength":1000}},"verified_claims":{"type":"array","maxItems":8,"items":{"type":"string","maxLength":1000}}},"required":["approved","reviewed_ids","issues","verified_claims"],"additionalProperties":false}}}""") as JsonObject
    val probeFormat = Json.parseToJsonElement("""{"type":"json_schema","json_schema":{"name":"schema_probe","strict":true,"schema":{"type":"object","properties":{"ok":{"type":"boolean","enum":[true]}},"required":["ok"],"additionalProperties":false}}}""") as JsonObject
}
