package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JVM entrypoint used by check_diagnostic_roundtrip.py; uses the production spool and sanitizer. */
fun main(args: Array<String>) {
    val directory = File(args[1])
    val spool = GatewayDiagnosticSpool(File(directory, "phone.jsonl"), "b".repeat(32), { 1_800_000_000_000L })
    if (args[0] == "emit") {
        val attributes = GatewayDiagnosticSchema.enumAttributes.flatMap { (name, values) -> values.sorted().map { buildJsonObject { put(name, it) } } } +
            GatewayDiagnosticSchema.numericAttributes.sorted().map { buildJsonObject { put(it, 3) } } +
            GatewayDiagnosticSchema.digestAttributes.sorted().map { buildJsonObject { put(it, "a".repeat(64)) } } +
            GatewayDiagnosticSchema.boolAttributes.sorted().flatMap { name -> (if (name == "vendor_log_available") listOf(false) else listOf(false, true)).map { buildJsonObject { put(name, it) } } }
        File(directory, "expected.json").writeText(JsonArray(attributes).toString())
        attributes.chunked(64).forEachIndexed { index, chunk ->
            spool.append(
                chunk.map {
                    1_800_000_000_000L to buildJsonObject {
                        put("stage", "transport")
                        put("bounded_attributes", it)
                    }
                }
            )
            val batch = spool.batch()
            File(directory, "batch-$index.json").writeText(batch.toString())
            val last = (batch["events"] as JsonArray).last() as JsonObject
            spool.acknowledge(
                buildJsonObject {
                    put("schema_version", 2)
                    put("session_id", spool.sessionId)
                    put("storage_state", "committed")
                    put(
                        "watermarks",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("origin_device_pseudonym", spool.originId)
                                    put("origin_boot_id", "b".repeat(32))
                                    put("accounted_through", last.diagnosticLong("origin_sequence")!!)
                                }
                            )
                        )
                    )
                },
                batch
            )
        }
    } else {
        // Import a committed gateway source through the phone's real sanitizer and persistence path.
        directory.listFiles()!!.filter { it.name.startsWith("page-") }.sortedBy { it.name.removePrefix("page-").removeSuffix(".json").toInt() }.forEach {
            spool.importPage(Json.parseToJsonElement(it.readText()) as JsonObject)
        }
        spool.export(File(directory, "phone-export.zip"))
    }
}
