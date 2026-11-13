package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppAuditExportFixtureCatalogTest {
    @Test
    fun compiledFixturePlansRetainTheirAllowlistedIdentitiesAndTypedReasons() = runBlocking {
        val sources = listOf(AppAuditBehaviorProbeSource(), AppAuditAppearanceProbeSource(), AppAuditResearchProbeSource(), AppAuditMemoryProbeSource())
        val plan = sources.flatMap { it.plan(AppAuditOptions()) }
        assertEquals(12, plan.size)
        val run = AppAuditRun(
            "a4772c4f-25b9-44b0-81cd-1e14cadbdb7c", 1, "c".repeat(64), "a".repeat(40), "0.9.66.6", "debug",
            AppAuditSourceInventory.sourceDigest, AppAuditSourceInventory.fileCount, AppAuditSourceInventory.lineCount, plan,
            sourceAreas = AppAuditSourceInventory.areas,
            // The source behavior has separate regressions. These synthetic rows exercise
            // only the export contract against its actual compiled plan descriptors.
            results = plan.mapIndexed { index, entry ->
                AppAuditResult(
                    entry.id,
                    AppAuditObservation(
                        if (index % 2 == 0) AppAuditOutcome.PASS else AppAuditOutcome.FAIL,
                        if (index % 2 == 0) "LOCAL_CONTRACT_PASSED" else "LOCAL_CONTRACT_FAILED",
                        stage = "fixture",
                        evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
                        observationSource = AppAuditObservationSource.FIXTURE
                    ),
                    1,
                    2,
                    1,
                    attempted = true
                )
            }
        )
        val output = ByteArrayOutputStream()
        writeAppAuditExport(output, BenchmarkBatch(run.id, BenchmarkMode.APP_AUDIT, 1, emptyList(), audit = run))
        val document = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
        val checks = document["checks"]!!.jsonArray
        plan.forEachIndexed { index, entry ->
            val exported = checks[index].jsonObject
            assertEquals(entry.sourceId, exported["sourceId"]!!.jsonPrimitive.content)
            assertEquals(entry.probeId, exported["probeId"]!!.jsonPrimitive.content)
            assertEquals(entry.label, exported["label"]!!.jsonPrimitive.content)
            assertEquals(entry.componentId, exported["componentId"]!!.jsonPrimitive.content)
            assertEquals(entry.fixtureHash, exported["fixtureHash"]!!.jsonPrimitive.content)
        }
        assertFalse(output.toString("UTF-8").contains("UNKNOWN_REASON"))
        assertFalse(output.toString("UTF-8").contains("UNKNOWN_STAGE"))
        assertEquals("AUDIT_PROBE_CHANGED", safeAuditReason("AUDIT_PROBE_CHANGED"))
    }
}
