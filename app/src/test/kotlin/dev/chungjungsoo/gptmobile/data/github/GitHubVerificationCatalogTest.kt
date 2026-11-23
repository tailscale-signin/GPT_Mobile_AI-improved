package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubVerificationCatalogTest {
    @Test fun equallyRelevantReceiptsWithLegacyTimestampsCanBeRankedTogether() {
        val catalog = Json.parseToJsonElement(
            """[
                {"tool":"a","id":"absent","facts":{"/name":"same"}},
                {"tool":"a","id":"recent","facts":{"/name":"same"},"observed_at_ms":1795350030546},
                {"tool":"a","id":"null","facts":{"/name":"same"},"observed_at_ms":null},
                {"tool":"a","id":"invalid","facts":{"/name":"same"},"observed_at_ms":{}},
                {"tool":"a","id":"older","facts":{"/name":"same"},"observed_at_ms":1},
                {"tool":"b","id":"distinct","facts":{},"observed_at_ms":1795350030547}
            ]"""
        ) as JsonArray
        val rows = GitHubVerificationCatalog.select(catalog, "inspect names")
        assertEquals(listOf("recent", "distinct", "older", "absent", "null", "invalid").map(::JsonPrimitive), rows.map { it["id"] })
    }
}
