package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GatewayDiagnosticSupportBundleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = "a".repeat(32)
    private fun archive(name: String, sessionId: String = session, validDigest: Boolean = true): File {
        val file = temporary.newFile(name)
        val events = "{}\n".toByteArray()
        val manifest = buildJsonObject {
            put("schema_version", 2)
            put("session_id", sessionId)
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "events.jsonl")
                            put("bytes", events.size)
                            put("sha256", if (validDigest) MessageDigest.getInstance("SHA-256").digest(events).diagnosticHex() else "f".repeat(64))
                        }
                    )
                )
            )
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("events.jsonl"))
            zip.write(events)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
        return file
    }
    private fun manifest(file: File) = ZipFile(file).use { Json.parseToJsonElement(it.getInputStream(it.getEntry("manifest.json")).reader().readText()) as JsonObject }

    @Test fun pairedSourcesKeepTheirBytesAndDeclareIndependentCoverage() {
        val device = archive("device.zip")
        val gateway = archive("gateway.zip")
        val output = temporary.newFile("support.zip")
        GatewayDiagnosticSupportBundle.write(device, gateway, session, output, null)
        ZipFile(output).use { zip ->
            assertArrayEquals(device.readBytes(), zip.getInputStream(zip.getEntry("device-mirror.zip")).readBytes())
            assertArrayEquals(gateway.readBytes(), zip.getInputStream(zip.getEntry("gateway-committed.zip")).readBytes())
        }
        assertEquals(JsonPrimitive(false), manifest(output)["complete"])
        assertEquals("source_local_monotonic_duration_only", manifest(output).diagnosticText("latency_measurement"))
    }

    @Test fun missingGatewayStillProducesDeviceEvidenceAndBoundedFailure() {
        val output = temporary.newFile("support.zip")
        GatewayDiagnosticSupportBundle.write(archive("device.zip"), null, session, output, "DIAGNOSTIC_TRANSPORT_FAILURE")
        assertEquals("unavailable", manifest(output).diagnosticText("gateway_capture"))
        assertEquals("DIAGNOSTIC_TRANSPORT_FAILURE", manifest(output).diagnosticText("gateway_failure"))
    }

    @Test fun corruptWrongSessionAndHashMismatchHaveOneRecoverableValidationType() {
        val corrupt = temporary.newFile("bad.zip").apply { writeText("not a zip") }
        val device = archive("device.zip")
        listOf(corrupt, archive("wrong-session.zip", "b".repeat(32)), archive("wrong-hash.zip", validDigest = false)).forEachIndexed { index, gateway ->
            val output = temporary.newFile("support-$index.zip")
            try {
                GatewayDiagnosticSupportBundle.write(device, gateway, session, output, null)
                fail("Invalid remote archive must not be trusted")
            } catch (_: GatewayDiagnosticSupportBundle.InvalidGatewayExport) {
                GatewayDiagnosticSupportBundle.write(device, null, session, output, "INVALID_DIAGNOSTIC_EXPORT")
            }
            assertEquals("INVALID_DIAGNOSTIC_EXPORT", manifest(output).diagnosticText("gateway_failure"))
        }
    }
}
