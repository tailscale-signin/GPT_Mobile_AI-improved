package dev.chungjungsoo.gptmobile.data.health

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectLocalImportCodecTest {
    private val snapshot = HealthConnectLocalImport(
        importedAt = "2026-10-11T20:00:00Z",
        days = 7,
        summary = buildJsonObject {
            put("schema", HealthConnectTools.SCHEMA)
            put("status", "available")
            put("metrics", buildJsonObject { put("steps", buildJsonObject { put("status", "available") }) })
        }
    )

    @Test fun `valid imported health snapshot round trips locally`() {
        assertEquals(snapshot, HealthConnectLocalImportCodec.decode(HealthConnectLocalImportCodec.encode(snapshot)))
    }

    @Test fun `invalid schemas malformed data and oversized snapshots are rejected`() {
        assertNull(HealthConnectLocalImportCodec.decode("not-json".toByteArray()))
        assertNull(HealthConnectLocalImportCodec.decode(ByteArray(HealthConnectLocalImportCodec.MAX_BYTES + 1)))
        val invalidHealthSchema = snapshot.copy(summary = buildJsonObject { put("schema", "unknown") })
        assertTrue(runCatching { HealthConnectLocalImportCodec.encode(invalidHealthSchema) }.isFailure)
        assertTrue(runCatching { HealthConnectLocalImportCodec.encode(snapshot.copy(days = 31)) }.isFailure)
    }
}
