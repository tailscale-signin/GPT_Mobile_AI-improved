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

    @Test fun `five years of imported history survives complete backup archive round trip`() {
        val years = snapshot.copy(days = 1825)
        val source = java.io.File.createTempFile("health-import", ".json")
        val archive = java.io.File.createTempFile("health-backup", ".zip")
        val staging = java.io.File(archive.parentFile, "health-backup-stage-${System.nanoTime()}")
        try {
            source.writeBytes(HealthConnectLocalImportCodec.encode(years))
            val path = "internal/health-connect/local-import-v1"
            val manifest = dev.chungjungsoo.gptmobile.data.backup.CompleteBackupManifest(
                files = mapOf(path to source.length()),
                sections = setOf(dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection.HEALTH_DATA.name)
            )
            dev.chungjungsoo.gptmobile.data.backup.CompleteBackupArchive.write(archive, manifest, mapOf(path to source))
            dev.chungjungsoo.gptmobile.data.backup.CompleteBackupArchive.read(archive, staging, 32L * 1024 * 1024)
            assertEquals(years, HealthConnectLocalImportCodec.decode(java.io.File(staging, path).readBytes()))
        } finally {
            source.delete()
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test fun `invalid schemas malformed data and oversized snapshots are rejected`() {
        assertNull(HealthConnectLocalImportCodec.decode("not-json".toByteArray()))
        assertNull(HealthConnectLocalImportCodec.decode(ByteArray(HealthConnectLocalImportCodec.MAX_BYTES + 1)))
        val invalidHealthSchema = snapshot.copy(summary = buildJsonObject { put("schema", "unknown") })
        assertTrue(runCatching { HealthConnectLocalImportCodec.encode(invalidHealthSchema) }.isFailure)
        assertTrue(runCatching { HealthConnectLocalImportCodec.encode(snapshot.copy(days = 3651)) }.isFailure)
    }
}
