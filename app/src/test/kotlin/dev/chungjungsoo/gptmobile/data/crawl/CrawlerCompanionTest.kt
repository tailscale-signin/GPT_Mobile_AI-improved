package dev.chungjungsoo.gptmobile.data.crawl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CrawlerCompanionTest {
    @Test fun signatureMatchesThePythonCompanionCanonicalContract() {
        val payload = buildJsonObject {
            put("principal", "profile")
            put("root_run_id", "turn")
            put("ephemeral", true)
            put("expires_at", 1000)
        }
        assertEquals(JsonPrimitive("80a80b51cabb1ceee24ede11a924723521a24211ed57307b1449ff2fe5bfddda"), CrawlerCompanionGrant.sign(payload, ByteArray(32) { it.toByte() })["signature"])
    }

    @Test fun companionProtocolAndMissingEvidenceNeverBecomeArticleText() {
        assertThrows(IllegalArgumentException::class.java) { CrawlerCompanionEvidence.normalize(Json.parseToJsonElement("""{"schemaVersion":1,"operation":"fetch","data":{"job":{"state":"completed"}}}"""), "https://example.org") }
        assertThrows(IllegalArgumentException::class.java) { CrawlerCompanionEvidence.normalize(Json.parseToJsonElement("""{"schemaVersion":1,"operation":"fetch","error":{"code":"PERMISSION_DENIED"}}"""), "https://example.org") }
    }
}
