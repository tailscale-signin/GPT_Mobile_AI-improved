package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Every stored run is included; display filters and selected profiles never restrict export. */
internal fun benchmarkExport(raw: String, detailed: Boolean): String {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(DiagnosticRedactor.redact(value.content)) else value
        else -> value
    }
    val backup = json.parseToJsonElement(raw).jsonObject
    val activeRows = ((backup["scoreTable"] as? JsonObject)?.get("rows") as? JsonArray).orEmpty()
    val output = buildJsonObject {
        put("format", "gptmobile.benchmarks.v1")
        put("detail", if (detailed) "diagnostic" else "complete")
        put("exportedAt", System.currentTimeMillis())
        backup.forEach { (key, value) -> put(key, value) }
        if (detailed) {
            put("scoringNotes", "Scores use eligible comparable runs; missing dimensions remain unknown. Token estimates and native measurements are distinct. Raw per-trial evidence is retained in runs.")
            put(
                "perRunScores",
                buildJsonArray {
                    backup["runs"]?.jsonArray.orEmpty().forEach { element ->
                        val run = json.decodeFromJsonElement(BenchmarkRun.serializer(), element)
                        val standard = benchmarkRating(listOf(run), run.local)
                        val rating = if (run.mode == BenchmarkMode.DELEGATION) {
                            val delegated = delegationBenchmarkRating(listOf(run))
                            standard.copy(score = delegated.score, dimensions = delegated.dimensions, measuredWeight = delegated.dimensions.filter { it.score != null }.sumOf { it.weight })
                        } else {
                            standard
                        }
                        add(
                            buildJsonObject {
                                put("runId", run.id)
                                put(
                                    "activeScoreRows",
                                    JsonArray(
                                        activeRows.filter { row ->
                                            (row.jsonObject["sourceRunIds"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content == run.id }
                                        }
                                    )
                                )
                                put("completedWithoutInterruption", run.finished && !run.canceled && run.stoppedReason == null)
                                put("scope", "Isolated-run diagnostic; active eligible aggregate scores are in scoreTable")
                                put("grade", rating.grade)
                                put("score", rating.score?.let(::JsonPrimitive) ?: JsonNull)
                                put("measuredWeight", rating.measuredWeight)
                                put(
                                    "dimensions",
                                    buildJsonArray {
                                        rating.dimensions.forEach { dimension ->
                                            add(
                                                buildJsonObject {
                                                    put("name", dimension.label)
                                                    put("score", dimension.score?.let(::JsonPrimitive) ?: JsonNull)
                                                    put("weight", dimension.weight)
                                                    put("detail", dimension.detail)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    }
    return json.encodeToString(JsonElement.serializer(), redact(output))
}
