package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditExportTest {
    @Test
    fun auditExportKeepsTypedFailureAndTimingButDropsPrivateFreeTextAndModelFields() = runBlocking {
        val private = "PRIVATE_SECRET_987"
        val first = entry().copy(label = private, targetId = "https://private.invalid/$private", configFingerprint = private)
        val unknown = entry("second").copy(sourceId = private, probeId = private, componentId = private, reasonCode = private, targetId = private)
        val run = run(listOf(first, unknown)).copy(
            stopReasonCode = private,
            options = AppAuditOptions(selectedProfileIds = listOf(private)),
            results = listOf(
                result(first.id, AppAuditOutcome.FAIL, "TLS_FAILURE").copy(durationMs = 91),
                result(unknown.id, AppAuditOutcome.FAIL, private).copy(observation = AppAuditObservation(AppAuditOutcome.FAIL, private, stage = private))
            )
        )
        val document = export(batch(run).copy(localEnvironment = private, storageWarning = private, recoveryPolicy = private))
        val json = document.toString()
        assertFalse(json.contains(private))
        assertFalse(json.contains("private.invalid"))
        assertEquals("TLS_FAILURE", document["results"]!!.jsonArray[0].jsonObject["reasonCode"]!!.jsonPrimitive.content)
        assertEquals(91, document["results"]!!.jsonArray[0].jsonObject["durationMs"]!!.jsonPrimitive.int)
        assertEquals("UNKNOWN_REASON", document["results"]!!.jsonArray[1].jsonObject["reasonCode"]!!.jsonPrimitive.content)
        assertEquals("UNKNOWN_STAGE", document["results"]!!.jsonArray[1].jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals("target-1", document["checks"]!!.jsonArray[0].jsonObject["targetAlias"]!!.jsonPrimitive.content)
        assertEquals(1, document["diagnostics"]!!.jsonObject["unknownReasonCodes"]!!.jsonPrimitive.int)
        assertFalse(document.containsKey("items"))
    }

    @Test
    fun aliasesFollowFrozenFirstOccurrenceAndJoinResults() = runBlocking {
        val plan = listOf(entry("a").copy(targetId = "second"), entry("b").copy(targetId = "first"), entry("c").copy(targetId = "second"))
        assertEquals(mapOf("second" to "target-1", "first" to "target-2"), appAuditTargetAliases(plan))
        val document = export(batch(run(plan).copy(results = listOf(result("c", AppAuditOutcome.PASS)))))
        val checks = document["checks"]!!.jsonArray
        assertEquals(checks[0].jsonObject["targetAlias"], checks[2].jsonObject["targetAlias"])
        assertEquals(checks[2].jsonObject["id"], document["results"]!!.jsonArray.single().jsonObject["checkId"])
    }

    @Test
    fun configurationAndCacheDoNotEarnMeasuredPassScore() = runBlocking {
        val config = entry("config").copy(evidenceKind = AppAuditEvidenceKind.CONFIGURATION)
        val cached = entry("cached")
        val skipped = entry("pending")
        val run = run(listOf(config, cached, skipped)).copy(
            results = listOf(
                result("config", AppAuditOutcome.PASS).copy(observation = AppAuditObservation(AppAuditOutcome.PASS, "PLUGIN_CONFIGURATION_ONLY", observationSource = AppAuditObservationSource.CONFIGURATION)),
                result("cached", AppAuditOutcome.PASS).copy(observation = AppAuditObservation(AppAuditOutcome.PASS, "MCP_DISCOVERY_VERIFIED", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = AppAuditObservationSource.CACHE))
            )
        )
        val document = export(batch(run))
        assertEquals(JsonNull, document["score"]!!.jsonObject["value"])
        assertEquals(0, document["coverage"]!!.jsonObject["exercised"]!!.jsonPrimitive.int)
        assertEquals(3, document["coverage"]!!.jsonObject["exercisedDenominator"]!!.jsonPrimitive.int)
        assertEquals(1, document["coverage"]!!.jsonObject["untested"]!!.jsonPrimitive.int)
        assertFalse(document["sourceCoverage"]!!.jsonObject["lineCoverageAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, document["sourceCoverage"]!!.jsonObject["lineCoveragePercent"])
    }

    @Test
    fun scoreAndBothCoverageDenominatorsRemainExplicitOnPartialRun() = runBlocking {
        val run = run(listOf(entry("pass"), entry("fail"), entry("pending"))).copy(results = listOf(result("pass", AppAuditOutcome.PASS), result("fail", AppAuditOutcome.FAIL)))
        val document = export(batch(run).copy(state = BenchmarkBatchState.STOPPED))
        assertEquals("STOPPED", document["run"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals(50.0, document["score"]!!.jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2, document["coverage"]!!.jsonObject["exercised"]!!.jsonPrimitive.int)
        assertEquals(1, document["coverage"]!!.jsonObject["verified"]!!.jsonPrimitive.int)
        assertEquals(3, document["coverage"]!!.jsonObject["verifiedDenominator"]!!.jsonPrimitive.int)
    }

    @Test
    fun actualGeneratedInventoryIsHashMatchedAndAttachedWithoutSourceText() = runBlocking {
        val asset = inventoryAsset().readBytes()
        val parsed = Json.parseToJsonElement(asset.decodeToString()).jsonObject
        assertEquals(AppAuditSourceInventory.sourceDigest, appAuditInventoryDigest(parsed["files"]!!.jsonArray.map { it.jsonObject }))
        val document = export(batch(run()), ByteArrayInputStream(asset))
        assertEquals("MATCHED_BUILD_INVENTORY", document["sourceCoverage"]!!.jsonObject["fileInventoryStatus"]!!.jsonPrimitive.content)
        assertEquals(AppAuditSourceInventory.fileCount, document["sourceFiles"]!!.jsonArray.size)
        assertTrue(document["sourceFiles"]!!.jsonArray.all { it.jsonObject.keys == setOf("area_id", "indexed_lines", "kind", "path", "sha256") })
        assertEquals(parsed["files"], document["sourceFiles"])
    }

    @Test
    fun historicalRunNeverAttachesAnotherBuildsInventoryOrReadsIt() = runBlocking {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int {
                reads++
                throw AssertionError("Mismatched inventory must not be read")
            }
        }
        val document = export(batch(run().copy(sourceInventoryDigest = "1".repeat(64), sourceFileCount = 7, sourceLineCount = 99)), input)
        assertEquals(0, reads)
        assertEquals("SOURCE_INVENTORY_BUILD_MISMATCH", document["sourceCoverage"]!!.jsonObject["fileInventoryStatus"]!!.jsonPrimitive.content)
        assertEquals(7, document["sourceCoverage"]!!.jsonObject["indexedFiles"]!!.jsonPrimitive.int)
        assertEquals(99, document["sourceCoverage"]!!.jsonObject["indexedTextLines"]!!.jsonPrimitive.int)
        assertTrue(document["sourceFiles"]!!.jsonArray.isEmpty())
    }

    @Test
    fun tamperedInventoryAndPrivateUnknownFieldsAreExcluded() = runBlocking {
        val original = Json.parseToJsonElement(inventoryAsset().readText()).jsonObject
        val files = original["files"]!!.jsonArray.toMutableList()
        val first = files.first().jsonObject
        for (replacement in listOf(
            JsonObject(first + ("path" to JsonPrimitive("app/src/main/../PRIVATE_SECRET"))),
            JsonObject(first + ("private_note" to JsonPrimitive("PRIVATE_SECRET"))),
            JsonObject(first + ("sha256" to JsonPrimitive("0".repeat(64))))
        )) {
            files[0] = replacement
            val input = ByteArrayInputStream(JsonObject(original + ("files" to JsonArray(files))).toString().encodeToByteArray())
            val document = export(batch(run()), input)
            assertTrue(document["sourceFiles"]!!.jsonArray.isEmpty())
            assertFalse(document.toString().contains("PRIVATE_SECRET"))
            assertFalse(document["sourceCoverage"]!!.jsonObject["fileInventoryStatus"]!!.jsonPrimitive.content == "MATCHED_BUILD_INVENTORY")
        }
    }

    @Test
    fun oversizedInventoryIsBoundedAndOtherRunEvidenceStillExports() = runBlocking {
        var bytesRead = 0
        val input = object : InputStream() {
            override fun read(): Int {
                bytesRead++
                return ' '.code
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(' '.code.toByte(), offset, offset + length)
                bytesRead += length
                return length
            }
        }
        val document = export(batch(run()), input)
        assertTrue(bytesRead <= 2 * 1024 * 1024 + 8192)
        assertEquals("SOURCE_INVENTORY_BYTE_LIMIT", document["sourceCoverage"]!!.jsonObject["fileInventoryStatus"]!!.jsonPrimitive.content)
        assertEquals(1, document["checks"]!!.jsonArray.size)
    }

    @Test
    fun nonProgressingInventoryIsRejectedAfterABoundedNumberOfReads() = runBlocking {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("The bulk read fixture must be used")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                check(reads <= 8) { "Inventory reader spun without a progress bound" }
                return 0
            }
        }
        val document = export(batch(run()), input)
        assertEquals(8, reads)
        assertEquals("SOURCE_INVENTORY_NO_PROGRESS", document["sourceCoverage"]!!.jsonObject["fileInventoryStatus"]!!.jsonPrimitive.content)
        assertTrue(document["sourceFiles"]!!.jsonArray.isEmpty())
        assertEquals(1, document["checks"]!!.jsonArray.size)
    }

    @Test
    fun recoveredUnknownDurationRemainsUnmeasured() = runBlocking {
        val recovered = result("entry", AppAuditOutcome.CANCELED, "PROCESS_INTERRUPTION").copy(durationMs = null)
        val document = export(batch(run().copy(results = listOf(recovered))))
        assertEquals(JsonNull, document["results"]!!.jsonArray.single().jsonObject["durationMs"])
        assertEquals(JsonNull, document["score"]!!.jsonObject["value"])
    }

    @Test
    fun truncatedPlanCannotProduceAnApparentlyCompleteScore() = runBlocking {
        val plan = (0..4104).map { entry("check-$it") }
        val run = run(plan).copy(results = plan.map { result(it.id, AppAuditOutcome.PASS) })
        val document = export(batch(run))
        assertEquals(4096, document["checks"]!!.jsonArray.size)
        assertEquals(9, document["diagnostics"]!!.jsonObject["omittedPlanEntries"]!!.jsonPrimitive.int)
        assertEquals(9, document["diagnostics"]!!.jsonObject["omittedResults"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, document["score"]!!.jsonObject["value"])
        assertEquals("EXPORT_EVIDENCE_TRUNCATED", document["score"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(4105, document["coverage"]!!.jsonObject["verifiedDenominator"]!!.jsonPrimitive.int)
        assertFalse(document["coverage"]!!.jsonObject["completeRetainedEvidence"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun completeOrdinaryPluginPlanExportsBeyondTheOldFiveHundredCheckLimit() = runBlocking {
        val source = AppAuditPluginProbeSource({
            (0 until 200).map { AppAuditPluginTarget("target-$it", AppAuditPluginKind.MCP, "mcp_connection", true, true, "fingerprint") }
        })
        val plan = source.plan(AppAuditOptions())
        assertEquals(600, plan.size)
        val document = export(batch(run(plan)))
        assertEquals(plan.size, document["checks"]!!.jsonArray.size)
        assertEquals(0, document["diagnostics"]!!.jsonObject["omittedPlanEntries"]!!.jsonPrimitive.int)
        assertTrue(document["coverage"]!!.jsonObject["completeRetainedEvidence"]!!.jsonPrimitive.boolean)
        assertEquals(600, document["coverage"]!!.jsonObject["untested"]!!.jsonPrimitive.int)
    }

    @Test
    fun destinationFailureLeavesCallerStreamOpenAndReleasesExportGate() = runBlocking {
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int): Unit = throw IOException("destination unavailable")
            override fun close() {
                closed = true
            }
        }
        val failure = runCatching { writeAppAuditExport(output, batch(run())) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(closed)
        assertEquals(1, export(batch(run()))["checks"]!!.jsonArray.size)
    }

    @Test
    fun cancellationReleasesGateAndDoesNotCloseCallerStreams() = runBlocking {
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(inventoryAsset().readBytes()) {
            override fun close() {
                inputClosed = true
            }
        }
        val job = launch {
            val owner = currentCoroutineContext()[Job]!!
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    owner.cancel(CancellationException("caller canceled export"))
                }
                override fun close() {
                    outputClosed = true
                }
            }
            writeAppAuditExport(output, batch(run()), input)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(inputClosed)
        assertFalse(outputClosed)
        assertEquals(1, export(batch(run()))["checks"]!!.jsonArray.size)
    }

    @Test
    fun inventoryDigestMatchesAsciiCanonicalJsonIncludingUnicodeEscapes() {
        val record = buildJsonObject {
            put("path", "app/src/main/café/😀.kt")
            put("sha256", "a".repeat(64))
            put("kind", "source")
            put("indexed_lines", 12)
            put("area_id", "other_production")
        }
        val canonical = "[{\"area_id\":\"other_production\",\"indexed_lines\":12,\"kind\":\"source\",\"path\":\"app/src/main/caf\\u00e9/\\ud83d\\ude00.kt\",\"sha256\":\"${"a".repeat(64)}\"}]"
        val expected = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, appAuditInventoryDigest(listOf(record)))
    }

    @Test
    fun exportSeparatesBudgetAdmissionsFromConfirmedAndUnknownNetworkDispatch() = runBlocking {
        val plan = listOf(entry("local"), entry("confirmed"), entry("unknown"), entry("cached"))
        val receipts = listOf(
            result("local", AppAuditOutcome.FAIL, "MCP_LOCAL_CREDENTIAL_INVALID").let { it.copy(observation = it.observation.copy(observationSource = AppAuditObservationSource.NONE, dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED)) },
            result("confirmed", AppAuditOutcome.PASS),
            result("unknown", AppAuditOutcome.FAIL, "MCP_DISCOVERY_UNAVAILABLE").let { it.copy(observation = it.observation.copy(dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN)) },
            result("cached", AppAuditOutcome.BLOCKED, "MCP_DISCOVERY_SCHEMA_INVALID").let { it.copy(observation = it.observation.copy(observationSource = AppAuditObservationSource.CACHE, dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED)) }
        )
        val document = export(batch(run(plan).copy(results = receipts, networkProbesDispatched = 3, networkProbesAttempted = 3)))
        val counters = document["run"]!!.jsonObject
        assertEquals(2, counters["networkAccountingVersion"]!!.jsonPrimitive.int)
        assertEquals(3, counters["networkProbesAttempted"]!!.jsonPrimitive.int)
        assertEquals(1, counters["networkProbesDispatched"]!!.jsonPrimitive.int)
        assertEquals(1, counters["networkProbesConfirmed"]!!.jsonPrimitive.int)
        assertEquals(1, counters["networkProbesDispatchUnknown"]!!.jsonPrimitive.int)
        val kinds = document["evidenceByKind"]!!.jsonArray.map { it.jsonObject }.associateBy { it["evidenceKind"]!!.jsonPrimitive.content }
        assertEquals(1, kinds.getValue("LIVE_METADATA")["verified"]!!.jsonPrimitive.int)
        assertEquals(0, kinds.getValue("TOOL_EXECUTION")["verified"]!!.jsonPrimitive.int)
    }

    @Test
    fun safeCachedFailureDetailIsPreservedAndPrivateDiagnosticKeysAreExcluded() = runBlocking {
        val entry = entry().copy(configurationRevision = "a".repeat(64))
        val known = result(entry.id, AppAuditOutcome.BLOCKED, "MCP_DISCOVERY_SCHEMA_INVALID").let {
            it.copy(
                observation = it.observation.copy(
                    observationSource = AppAuditObservationSource.CACHE,
                    dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
                    retryable = false, httpStatus = 200,
                    failurePhase = "protocol_negotiation", failureCategory = "PROTOCOL_CONFIGURATION",
                    validationRule = "PROTOCOL_VERSIONS_ARRAY_REQUIRED", validationField = "supportedVersions",
                    observedAtEpochMs = 1, configurationRevision = "a".repeat(64)
                )
            )
        }
        val saved = export(batch(run(listOf(entry)).copy(results = listOf(known))))
        val receipt = saved["results"]!!.jsonArray.single().jsonObject
        assertEquals("protocol_negotiation", receipt["failurePhase"]!!.jsonPrimitive.content)
        assertEquals("PROTOCOL_CONFIGURATION", receipt["failureCategory"]!!.jsonPrimitive.content)
        assertEquals("PROTOCOL_VERSIONS_ARRAY_REQUIRED", receipt["validationRule"]!!.jsonPrimitive.content)
        assertEquals("supportedVersions", receipt["validationField"]!!.jsonPrimitive.content)
        assertEquals(1, receipt["observedAtEpochMs"]!!.jsonPrimitive.int)
        assertEquals(10, receipt["startedAtEpochMs"]!!.jsonPrimitive.int)
        assertEquals("a".repeat(64), receipt["configurationRevision"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(64), saved["checks"]!!.jsonArray.single().jsonObject["configurationRevision"]!!.jsonPrimitive.content)
        val private = "PRIVATE_DIAGNOSTIC_VALUE"
        val poisoned = known.copy(observation = known.observation.copy(failurePhase = private, failureCategory = private, validationRule = private, validationField = private))
        val sanitized = export(batch(run(listOf(entry)).copy(results = listOf(poisoned))))
        assertFalse(sanitized.toString().contains(private))
        for (field in listOf("failurePhase", "failureCategory", "validationRule", "validationField")) {
            assertEquals(JsonNull, sanitized["results"]!!.jsonArray.single().jsonObject[field])
        }
    }

    private fun entry(id: String = "entry") = AppAuditPlanEntry(
        id, "plugins", AppAuditPluginProbeSource.MCP_DISCOVERY, "mcp_connection", "MCP discovery",
        targetId = "private-target", sourceAreaIds = listOf("mcp_plugins"), evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
        safetyClass = AppAuditSafetyClass.NETWORK_READ
    )

    private fun result(id: String, outcome: AppAuditOutcome, reason: String = "MCP_DISCOVERY_VERIFIED") = AppAuditResult(
        id,
        AppAuditObservation(outcome, reason, stage = "mcp_discovery", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = AppAuditObservationSource.LIVE, dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED),
        10,
        20,
        10,
        attempted = true
    )

    private fun run(plan: List<AppAuditPlanEntry> = listOf(entry())) = AppAuditRun(
        "e1a7be4a-1169-45dc-8792-08c6ace27747", 1, "b".repeat(64), "c".repeat(40), "0.5.0", "debug",
        AppAuditSourceInventory.sourceDigest, AppAuditSourceInventory.fileCount, AppAuditSourceInventory.lineCount, plan,
        sourceAreas = AppAuditSourceInventory.areas
    )

    private fun batch(run: AppAuditRun) = BenchmarkBatch(run.id, BenchmarkMode.APP_AUDIT, 1, emptyList(), audit = run)

    private suspend fun export(batch: BenchmarkBatch, input: InputStream? = null): JsonObject {
        val output = ByteArrayOutputStream()
        writeAppAuditExport(output, batch, input)
        return Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
    }

    private fun inventoryAsset(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .flatMap { root -> sequenceOf(File(root, "app/src/main/assets/${AppAuditSourceInventory.assetPath}"), File(root, "src/main/assets/${AppAuditSourceInventory.assetPath}")) }
        .first { it.isFile }
}
