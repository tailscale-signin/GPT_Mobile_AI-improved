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

/** Captured immutable references; raw payload records are supplied separately and streamed. */
internal data class BenchmarkExportSource(
    val runs: List<BenchmarkRun>,
    val batches: List<BenchmarkBatch>,
    val scoreSnapshot: BenchmarkScoreSnapshot?,
    val enrollment: Map<String, String>?,
    val rawScoreTable: JsonElement?,
    val storageSchemaVersion: JsonElement = JsonPrimitive(3),
    val exportedAt: Long = System.currentTimeMillis(),
    /** Cold exports retain only comparison projections in runs; grading always reloads a full run. */
    val readCompleteRuns: (() -> Sequence<BenchmarkRun>)? = null
) {
    fun completeRuns(): Sequence<BenchmarkRun> = readCompleteRuns?.invoke() ?: runs.asSequence()
}

internal val benchmarkExportJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

internal val benchmarkExportMetadata = setOf("format", "schemaVersion", "storageSchemaVersion", "detail", "exportedAt", "snapshotState", "measurementPolicy", "measurementPolicies", "scoringPolicy", "units", "perRunMetrics", "perRunScores", "scoringNotes", "scoreSelection", "policyDomains", "coverage", "nativeSegmentMeasurements", "runtimeEvidenceScope", "performanceComparisonMatrix", "intelligenceFixtureScope")

/** Compatibility helper for retained JSON callers; app exports use the streaming store API. */
internal fun benchmarkExport(raw: String, detailed: Boolean): String {
    val json = benchmarkExportJson
    val backup = json.parseToJsonElement(raw).jsonObject
    val runs = backup["runs"]?.jsonArray.orEmpty().map { json.decodeFromJsonElement(BenchmarkRun.serializer(), it) }
    val batches = backup["batches"]?.jsonArray.orEmpty().map { json.decodeFromJsonElement(BenchmarkBatch.serializer(), it) }
    val snapshot = backup["scoreTable"]?.let { runCatching { json.decodeFromJsonElement(BenchmarkScoreSnapshot.serializer(), it) }.getOrNull() }
    val enrollment = (backup["enrollment"] as? JsonObject)?.mapValues { (_, value) -> (value as JsonPrimitive).content }
    val source = BenchmarkExportSource(runs, batches, snapshot, enrollment, backup["scoreTable"], backup["storageSchemaVersion"] ?: backup["schemaVersion"] ?: JsonPrimitive(1))
    val fields = linkedMapOf<String, JsonElement>()
    val output = object : BenchmarkExportOutput {
        override fun put(name: String, value: JsonElement) {
            fields[name] = value
        }
        override fun put(name: String, records: BenchmarkExportRecords) {
            val values = mutableListOf<JsonElement>()
            records.writeTo(values::add)
            fields[name] = JsonArray(values)
        }
    }
    writeBenchmarkExport(output, source, detailed) { sink ->
        backup.forEach { (key, value) ->
            if (key !in benchmarkExportMetadata && (detailed || key !in setOf("scoreHistory", "batchEvents"))) {
                sink.put(
                    key,
                    when (key) {
                        "runs" -> preserveBenchmarkExportUnknown(value, json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(BenchmarkRun.serializer()), runs))
                        "batches" -> preserveBenchmarkExportUnknown(value, json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(BenchmarkBatch.serializer()), batches))
                        else -> value
                    }
                )
            }
        }
    }
    return json.encodeToString(JsonElement.serializer(), redactBenchmarkExport(JsonObject(fields)))
}

internal fun preserveBenchmarkExportUnknown(original: JsonElement?, normalized: JsonElement): JsonElement = when {
    original is JsonObject && normalized is JsonObject -> JsonObject(original + normalized.mapValues { (key, value) -> preserveBenchmarkExportUnknown(original[key], value) })
    original is JsonArray && normalized is JsonArray && original.size == normalized.size -> JsonArray(normalized.mapIndexed { index, value -> preserveBenchmarkExportUnknown(original[index], value) })
    else -> normalized
}

internal fun redactBenchmarkExport(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(
        value.mapValues { (key, item) ->
            when {
                key.lowercase() in setOf("token", "secretref", "apikey", "api_key", "authorization", "cookie", "password") -> JsonNull
                key.lowercase() in setOf("apiurl", "endpoint", "baseurl") && item is JsonPrimitive && item.isString -> JsonPrimitive(runCatching { java.net.URI(item.content).let { uri -> java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString() } }.getOrDefault("[redacted endpoint]"))
                else -> redactBenchmarkExport(item)
            }
        }
    )
    is JsonArray -> JsonArray(value.map(::redactBenchmarkExport))
    is JsonPrimitive -> if (value.isString) JsonPrimitive(DiagnosticRedactor.redact(value.content)) else value
    else -> value
}

/** Each deferred array emits one item at a time, including per-run diagnostics. */
internal fun writeBenchmarkExport(output: BenchmarkExportOutput, source: BenchmarkExportSource, detailed: Boolean, writeStoredFields: (BenchmarkExportOutput) -> Unit) {
    val json = benchmarkExportJson
    val runs = source.runs
    val batches = source.batches
    val scoreSnapshot = source.scoreSnapshot
    val enrollment = source.enrollment
    val activeRows = ((source.rawScoreTable as? JsonObject)?.get("rows") as? JsonArray).orEmpty()
    with(output) {
        put("format", "gptmobile.benchmarks.v2")
        put("schemaVersion", 2)
        put("storageSchemaVersion", source.storageSchemaVersion)
        put("detail", if (detailed) "diagnostic" else "complete")
        put("exportedAt", source.exportedAt)
        put("snapshotState", if (runs.any { !it.finished } || batches.any { it.state in setOf(BenchmarkBatchState.QUEUED, BenchmarkBatchState.RUNNING) }) "IN_PROGRESS_AT_EXPORT" else "COMPLETE")
        val measurements = runs.map { it.measurementPolicy ?: "benchmark-measurement-v${it.measurementVersion}" }.distinct()
        put("measurementPolicy", measurements.singleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("measurementPolicies", JsonArray(measurements.map(::JsonPrimitive)))
        put("scoringPolicy", scoreSnapshot?.policy?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "runtimeEvidenceScope",
            buildJsonObject {
                put("runtimeDecisions", "Unjoined runtime-wide planning history; inclusion here does not attribute a decision to a benchmark request.")
                put("runtimeTrials", "Retained runtime qualification and calibration history; only an exact receipt identity joins it to a case.")
                put("runtimeExecutions", "Per-sample receipts are joined by physical request, runtime request, worker and native operation; load and generation configuration remain separate.")
            }
        )
        put("scoreSelection", benchmarkExportRecords(benchmarkRunSelection(runs, scoreSnapshot, enrollment).asSequence().map { json.encodeToJsonElement(BenchmarkRunSelection.serializer(), it) }))
        put(
            "policyDomains",
            benchmarkExportRecords(
                source.completeRuns().map { run ->
                    buildJsonObject {
                        put("runId", run.id)
                        put("fixtureVersion", run.suiteVersion)
                        put("executionPolicy", run.executionPolicy?.name?.let(::JsonPrimitive) ?: JsonNull)
                        put("deadlinePolicy", run.deadlinePolicy?.let(::JsonPrimitive) ?: JsonNull)
                        put("fixtureRotationSeed", run.fixtureRotationSeed?.let(::JsonPrimitive) ?: JsonNull)
                        put("fixtureSetVersion", run.fixtureSetVersion?.let(::JsonPrimitive) ?: JsonNull)
                        put("measurementCompatibility", run.measurementVersion)
                        put("runScoringCompatibility", run.scoringVersion)
                        put("aggregatePolicy", run.aggregatePolicy?.let(::JsonPrimitive) ?: JsonNull)
                        put("validatorVersions", JsonArray(run.samples.map { it.validatorVersion }.distinct().map(::JsonPrimitive)))
                        put("derivedMetricPolicies", JsonArray(benchmarkMetricResults(listOf(run)).map { it.policyVersion }.distinct().map(::JsonPrimitive)))
                        put("cohort", DynamicScoreEngine.cohort(run))
                        put("timingContextExclusion", benchmarkTimingExecutionExclusion(run)?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
            )
        )
        put(
            "coverage",
            benchmarkExportRecords(
                source.completeRuns().map { run ->
                    buildJsonObject {
                        put("runId", run.id)
                        put("counts", json.encodeToJsonElement(BenchmarkCoverage.serializer(), benchmarkCoverage(run)))
                    }
                }
            )
        )
        put(
            "units",
            buildJsonObject {
                put("referenceSpeed", "reference text units/s; never native model tokens")
                put("providerTokens", "reported only when explicitly supplied; otherwise unknown or estimated")
                put("clientPssKb", "Android client process PSS; excludes remote server memory")
                put("physicalRequestIds", "Admitted generation attempt IDs; admission does not prove network bytes were sent. Empty means unavailable.")
                put("phaseElapsedMs", "Sequential client-observed intervals from the attempt monotonic clock, including Android deep sleep. Native segment timing is separate.")
                put("tokenConfidence", "final_reported is measured final provider/runtime usage; partial_observation, estimated, mixed and unknown are not final measured counts.")
                put("nativeIdentity", "Worker/session and lease identities join the attempt. Null actual engine/driver/offload fields are unknown; configured settings are not actual execution evidence.")
                put("runtimeCleanup", "Independent positive evidence of native/conversation closure or confirmed worker exit. Termination intent and answer completion do not prove safe handoff.")
                put("resourceScope", "Execution observations and before/after cleanup are distinct; stored peaks are derived from the same retained observations. PSS, allocation bytes and GPU estimates must not be summed as disjoint memory.")
                put("nativeSegmentMeasurements", "Canonical nullable view of legacy timing aliases; original fields remain unchanged in runs for round-trip compatibility. Native rates are per measured segment and model tokenizer dependent.")
            }
        )
        writeStoredFields(this)
        put(
            "nativeSegmentMeasurements",
            benchmarkExportRecords {
                source.completeRuns().forEach { run ->
                    run.samples.forEach { sample ->
                        sample.localSegments.forEach { segment ->
                            add(
                                buildJsonObject {
                                    put("runId", run.id)
                                    put("attemptId", sample.attemptId)
                                    put("segmentId", segment.segmentId)
                                    put("scope", "physical_generation_segment")
                                    put(
                                        "legacyTimingMeasurements",
                                        buildJsonObject {
                                            put("timeToFirstTokenMs", segment.timeToFirstTokenMs.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                                            put("totalChunks", segment.totalChunks.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                                            put("tokensPerSecond", segment.tokensPerSecond.takeIf { it.isFinite() && it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                                            put("status", "Legacy zero defaults are unmeasured or unknown; positive values are legacy reported observations, not native acknowledgement.")
                                        }
                                    )
                                    put("firstVisibleAnswerMs", segment.firstVisibleAnswerMs?.let(::JsonPrimitive) ?: JsonNull)
                                    put("decodeDurationMs", segment.decodeDurationMs?.let(::JsonPrimitive) ?: JsonNull)
                                    put("native", segment.native?.let { json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.localruntime.NativeInferenceMetrics.serializer(), it) } ?: JsonNull)
                                }
                            )
                        }
                    }
                }
            }
        )
        put("performanceComparisonMatrix", benchmarkExportRecords(benchmarkComparisonMatrix(runs).asSequence().map { json.encodeToJsonElement(BenchmarkComparisonRow.serializer(), it) }))
        put("intelligenceFixtureScope", BenchmarkIntelligenceFixtures.LIMITATION)
        put(
            "perRunMetrics",
            benchmarkExportRecords {
                source.completeRuns().forEach { run ->
                    add(
                        buildJsonObject {
                            put("runId", run.id)
                            put("resultId", benchmarkResultId(run))
                            put("state", if (!run.finished) "IN_PROGRESS_AT_EXPORT" else run.state)
                            put("metrics", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(BenchmarkMetricResult.serializer()), benchmarkMetricResults(listOf(run))))
                            put("intelligence", json.encodeToJsonElement(BenchmarkIntelligenceSummary.serializer(), benchmarkIntelligenceSummary(run)))
                            put("diagnosticScenarios", json.encodeToJsonElement(BenchmarkDiagnosticScenarioSummary.serializer(), benchmarkDiagnosticScenarioSummary(run)))
                            put("performance", json.encodeToJsonElement(BenchmarkPerformanceSummary.serializer(), benchmarkPerformanceSummary(run, runs)))
                            put("cleanupMemoryTrend", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(BenchmarkCleanupMemoryTrend.serializer()), benchmarkCleanupMemoryTrend(run)))
                            put("portableScoreboardIneligibility", benchmarkScoreboardIneligibility(run)?.let(::JsonPrimitive) ?: JsonNull)
                            put(
                                "executionEvidence",
                                buildJsonArray {
                                    run.samples.forEach { sample ->
                                        add(
                                            buildJsonObject {
                                                put("testId", sample.testId)
                                                put("attemptId", sample.attemptId)
                                                put("evidence", json.encodeToJsonElement(BenchmarkAcceleratorEvidence.serializer(), benchmarkAcceleratorEvidence(run, sample)))
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
        if (detailed) {
            put("scoringNotes", "Scores use eligible comparable runs; missing dimensions remain unknown. Token estimates and native measurements are distinct. Raw per-trial evidence is retained in runs.")
            put(
                "perRunScores",
                benchmarkExportRecords {
                    source.completeRuns().forEach { run ->
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
                                put(
                                    "timingExclusions",
                                    buildJsonArray {
                                        run.samples.forEach { sample ->
                                            sample.timingExclusionReason?.let { reason ->
                                                add(
                                                    buildJsonObject {
                                                        put("testId", sample.testId)
                                                        put("reason", reason)
                                                        put("requestCompleted", sample.completed)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                                put(
                                    "reasoningReevaluation",
                                    buildJsonArray {
                                        run.samples.forEach { sample ->
                                            IntelligenceBenchmark.gradeRetained(sample, sample.intelligenceFixtureVersion ?: if (run.suiteVersion >= 7) 3 else 2)?.let { grade ->
                                                add(
                                                    buildJsonObject {
                                                        put("testId", sample.testId)
                                                        put("fixtureVersion", grade.fixtureVersion)
                                                        put("rawJsonCompliant", grade.formatCompliant)
                                                        put("parseable", grade.parseable)
                                                        put("correct", if (grade.parseable) JsonPrimitive(grade.answers.values.count { it }) else JsonNull)
                                                        put("total", grade.answers.size)
                                                        put("answers", buildJsonObject { grade.answers.forEach { (key, value) -> put(key, if (grade.parseable) JsonPrimitive(value) else JsonNull) } })
                                                        grade.correction?.let { put("correction", it) }
                                                        put("scope", "Historical fixture compatibility re-evaluation of complete retained text; unparseable semantics are ungraded. Current semantic summaries are in perRunMetrics; original grades/raw evidence unchanged.")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                                put("completedWithoutInterruption", run.finished && !run.canceled && run.stoppedReason == null)
                                put("scope", "Isolated-run diagnostic; active eligible aggregate scores are in scoreTable")
                                put("scoringPolicy", "isolated-legacy-rating-diagnostic-v1")
                                put("baselineProvenance", "No cohort anchors supplied for this isolated diagnostic. Active aggregate rows carry compatible baseline evidence.")
                                put("grade", if (run.mode == BenchmarkMode.DELEGATION) rating.grade else "Observed metrics only")
                                put("ratingUnavailableReason", if (run.mode == BenchmarkMode.DELEGATION) "" else "Isolated run has no compatible speed and latency anchors; see activeScoreRows.")
                                put("score", if (run.mode == BenchmarkMode.DELEGATION) rating.score?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
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
}
