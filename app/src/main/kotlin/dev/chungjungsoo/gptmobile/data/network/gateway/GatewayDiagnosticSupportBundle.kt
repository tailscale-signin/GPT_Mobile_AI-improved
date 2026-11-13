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

/** Keep each committed source intact and disclose a failed remote fetch without losing the phone capture. */
internal object GatewayDiagnosticSupportBundle {
    class InvalidGatewayExport(cause: Exception) : java.io.IOException("INVALID_DIAGNOSTIC_EXPORT", cause)

    data class Retrieval(val protocol: Int?, val captureEnabled: Boolean?, val attempted: Boolean, val httpStatus: Int? = null)

    fun write(device: File, gateway: File?, session: String, destination: File, unavailable: String?, retrieval: Retrieval? = null) {
        if (gateway != null) {
            try {
                validateGateway(gateway, session)
            } catch (failure: Exception) {
                throw InvalidGatewayExport(failure)
            }
        }
        val parts = listOfNotNull("device-mirror.zip" to device, gateway?.let { "gateway-committed.zip" to it })
        ZipOutputStream(destination.outputStream()).use { zip ->
            val files = parts.map { (name, file) ->
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                buildJsonObject {
                    put("path", name)
                    put("bytes", file.length())
                    put("sha256", digest.digest().diagnosticHex())
                }
            }
            val manifest = buildJsonObject {
                put("schema", "gateway.linked-support.v1")
                put("session_id", session)
                put("schema_digest", GatewayDiagnosticSchema.DIGEST)
                put("files", JsonArray(files))
                put("gateway_capture", if (gateway != null) "committed_snapshot" else "unavailable")
                put("gateway_failure", unavailable?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                retrieval?.let {
                    put(
                        "gateway_retrieval",
                        buildJsonObject {
                            put("protocol_version", it.protocol)
                            put("capture_enabled", it.captureEnabled)
                            put("attempted", it.attempted)
                            put("http_status", it.httpStatus)
                            put(
                                "result",
                                if (gateway != null) {
                                    "received"
                                } else if (it.attempted) {
                                    "unavailable"
                                } else {
                                    "not_attempted"
                                }
                            )
                        }
                    )
                }
                put("complete", false)
                put("coverage", "Read each source manifest. Device run inventory and gateway job receipts have independent snapshot times. Source-event digests link to app diagnostics; full source payloads remain in their protected stores.")
                put("clock_ordering", "origin_sequence_and_causal_trace_only")
                put("latency_measurement", "source_local_monotonic_duration_only")
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun validateGateway(file: File, session: String) {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.map { it.name }.sorted() == listOf("events.jsonl", "manifest.json")) { "INVALID_DIAGNOSTIC_EXPORT" }
            require(entries.all { it.size in 0..12 * 1024 * 1024 } && entries.sumOf { it.size } <= 12 * 1024 * 1024) { "DIAGNOSTIC_RESPONSE_LIMIT" }
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).use { input ->
                require(zip.getEntry("manifest.json").size <= 1024 * 1024) { "DIAGNOSTIC_RESPONSE_LIMIT" }
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 1024 * 1024) { "DIAGNOSTIC_RESPONSE_LIMIT" }
                    bytes.write(buffer, 0, count)
                }
                Json.parseToJsonElement(bytes.toByteArray().toString(Charsets.UTF_8)) as? JsonObject ?: error("INVALID_DIAGNOSTIC_EXPORT")
            }
            require(manifest.diagnosticText("session_id") == session && manifest.diagnosticLong("schema_version") == 2L) { "INVALID_DIAGNOSTIC_EXPORT" }
            val expected = (manifest["files"] as? JsonArray)?.singleOrNull() as? JsonObject ?: error("INVALID_DIAGNOSTIC_EXPORT")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            zip.getInputStream(zip.getEntry("events.jsonl")).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= 12 * 1024 * 1024) { "DIAGNOSTIC_RESPONSE_LIMIT" }
                    digest.update(buffer, 0, count)
                }
            }
            require(expected.diagnosticText("path") == "events.jsonl" && expected.diagnosticLong("bytes") == bytes && expected.diagnosticText("sha256") == digest.digest().diagnosticHex()) { "INVALID_DIAGNOSTIC_EXPORT" }
        }
    }
}
