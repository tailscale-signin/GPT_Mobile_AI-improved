package dev.chungjungsoo.gptmobile.data.orchestration

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.SharedToolCallBroker
import dev.chungjungsoo.gptmobile.data.assist.AssistCoordinator
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paired production-wrapper regression, explicitly not live-provider quality/cost qualification. */
class UnifiedRuntimePairedFixtureTest {
    @Test fun randomizedPairsPreserveEvidenceAndCountEveryAttemptAcross102DistinctTasks(): Unit = runBlocking {
        val cohorts = listOf("short_chat", "long_document", "multi_source_research", "memory_retrieval", "structured_extraction", "combined_delegation")
        val rows = mutableListOf<JsonObject>()
        val random = Random(20261013)
        for (cohort in cohorts) {
            repeat(17) { task ->
                val id = "$cohort-$task"
                val goal = "For $id, retain source 0 and the contrary finding in source 2, including exact quantity ${task + 11} kg and revision v${task + 1}."
                val count = if (cohort == "short_chat") 3 else 12
                val records = JsonArray(
                    (0 until count).map { source ->
                        buildJsonObject {
                            put("source", "https://example.invalid/$id/$source")
                            put("revision", "v${task + 1}")
                            put("quantity", "${task + 11} kg")
                            put("claim", if (source == 2) "NOT approved; contrary evidence" else "Status unknown; no causal conclusion")
                            put("text", if (count == 3) "Short observation" else (if (task % 2 == 0) "観測 données source text. " else "Captured synthetic source evidence. ").repeat(16))
                        }
                    }
                )
                val original = ToolResultContent.Json(buildJsonObject { put("results", records) })
                repeat(2) { trial ->
                    for (optimized in listOf(false, true).shuffled(random)) {
                        var physicalReads = 0
                        var helperRequests = 0
                        val root = "paired:$id:$trial:${UUID.randomUUID()}"
                        val tool = object : AgentTool {
                            override val definition = AgentToolDefinition("fixture_read", "Immutable synthetic evidence", JsonObject(emptyMap()))
                            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                                physicalReads++
                                delay(1)
                                return AgentToolResult(callId, original, false)
                            }
                        }
                        val shared = SharedToolCallBroker().wrap(root, "fixture:v1:$id", optimized, tool)
                        val admitted = if (optimized) {
                            AssistCoordinator(goal, { true }, {
                                helperRequests++
                                "[0,2]"
                            }, {}).bind(shared, "synthetic-public")
                        } else {
                            shared
                        }
                        val started = System.nanoTime()
                        val results = coroutineScope { (0..1).map { index -> async { admitted.execute("peer-$index", JsonObject(emptyMap())) } }.awaitAll() }
                        val elapsed = System.nanoTime() - started
                        assertEquals(if (optimized) 1 else 2, physicalReads)
                        results.forEach { result ->
                            assertEquals(original, result.retainedContent ?: result.content)
                            val selected = ((result.content as ToolResultContent.Json).value as JsonObject)["results"] as JsonArray
                            assertTrue(selected.all { it in records })
                            assertTrue(records[0] in selected && records[2] in selected)
                        }
                        if (!optimized || count == 3) assertEquals(0, helperRequests) else assertTrue(helperRequests in 1..2)
                        rows += buildJsonObject {
                            put("taskId", id)
                            put("cohort", cohort)
                            put("trial", trial)
                            put("optimized", optimized)
                            put("physicalReads", physicalReads)
                            put("scriptedHelperRequests", helperRequests)
                            put("elapsedNanos", elapsed)
                            put("exactEvidencePreserved", true)
                            put("remoteTokens", JsonNull)
                            put("providerCost", JsonNull)
                        }
                    }
                }
            }
        }
        assertEquals(408, rows.size)
        val report = buildJsonObject {
            put("schemaVersion", 1)
            put("corpusVersion", "synthetic-orchestration-2026-10-13-v1")
            put("tasks", 102)
            put("attempts", rows.size)
            put("qualification", "scripted_wrapper_regression_only")
            put("liveProviderMeasured", false)
            put("physicalDeviceMeasured", false)
            put("modelQualityAndCost", "Unavailable; no real model requests or energy measurement")
            put("samples", JsonArray(rows))
        }
        File("build/reports/unified-runtime-paired.json").apply {
            parentFile.mkdirs()
            writeText(report.toString())
        }
    }
}
