package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.PreparedContextBudget
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskContextProjectionTest {
    private val sourceSha = "a".repeat(40)
    private val blobSha = "b".repeat(40)
    private val lines = (1..32).map { "    val parserResult$it = decode(input$it) // selected source 🧭" }

    private fun source(excerpt: List<String> = lines, start: Int = 21) = buildJsonObject {
        put("path", "src/Parser.kt")
        put("blob_sha", blobSha)
        put("start_line", start)
        put("end_line", start + excerpt.size - 1)
        put("excerpt", excerpt.joinToString("\n"))
        put("range_complete", true)
    }

    private fun receipt(range: JsonObject = source()) = buildJsonObject {
        put("source_sha", sourceSha)
        put("selected_ranges", JsonArray(listOf(range)))
        put("source_complete", true)
        put("source_completeness_scope", "ATTEMPTED_TASK_SELECTION_ONLY")
        put("selection_truncated", false)
        put(
            "coverage",
            buildJsonObject {
                put("tree_complete", false)
                put("selected_files", 1)
                put("selected_source_bytes", (range["excerpt"] as JsonPrimitive).content.toByteArray(Charsets.UTF_8).size)
                put("selected_source_lines", range.integer("end_line") - range.integer("start_line") + 1)
            }
        )
    }

    private fun render(value: JsonObject, tokens: Int) = GitHubTaskContextProjection.render("owner/repo", "selected", sourceSha, value, tokens)
    private fun data(result: GitHubTaskContextProjection.Result) = Json.parseToJsonElement(result.text.substringAfter('\n')) as JsonObject
    private fun JsonObject.integer(key: String) = (get(key) as JsonPrimitive).intOrNull!!

    @Test fun fourAndEightKRequestsRetainSourceWithinTheActualResidualAllowance() {
        // Prompt totals already include required system/history/memory; tool replay and output stay reserved.
        for ((capacity, output, prompt, replay) in listOf(listOf(4096, 512, 1961, 512), listOf(8192, 1024, 3847, 1024))) {
            val plan = ContextPlan(emptyList(), "Required system and admitted memory", emptyList(), replay * 3, output, "", promptTokens = prompt)
            val available = PreparedContextBudget.optionalTokens(plan, capacity, output)
            val input = if (capacity < 8192) {
                buildJsonObject {
                    put("source_window", source(lines.take(6)))
                    put("source_read_candidates", JsonArray((1..3).map { JsonPrimitive("src/" + "nested/".repeat(6) + "Parser$it.kt") }))
                }
            } else {
                receipt()
            }
            val complete = render(input, 4000)
            assertTrue(available in 256..1200)
            assertEquals("", PreparedContextBudget.sections(listOf(complete.text), available))

            val fitted = render(input, available)
            val admitted = PreparedContextBudget.sections(listOf(fitted.text, "Optional historical repair ".repeat(200)), available)
            assertTrue(fitted.source)
            assertEquals("\n${fitted.text}", admitted)
            assertTrue(ContextBudgetService.estimate(admitted) <= available)
            assertTrue(prompt.toLong() + replay + ContextBudgetService.estimate(admitted) + 64 <= RequestAdmission.maximumEstimatedPrompt(capacity.toLong() - output))
            assertEquals(replay * 3, plan.toolResultBytes)
            assertEquals(output, plan.outputTokens)

            val packet = data(fitted)
            assertEquals(JsonPrimitive(sourceSha), packet["source_sha"])
            assertEquals(JsonPrimitive(true), packet["selection_truncated"])
            val range = (packet["source_window"] ?: (packet["selected_ranges"] as JsonArray).single()) as JsonObject
            val excerpt = (range["excerpt"] as JsonPrimitive).content
            assertEquals(blobSha, (range["blob_sha"] as JsonPrimitive).content)
            assertEquals(lines.take(range.integer("end_line") - range.integer("start_line") + 1).joinToString("\n"), excerpt)
            assertFalse(excerpt.contains('\uFFFD'))
            if (capacity >= 8192) {
                assertEquals(JsonPrimitive(false), packet["tree_complete"])
                assertEquals(JsonPrimitive(false), packet["source_complete"])
                assertEquals(JsonPrimitive(1), (packet["context_omissions"] as JsonObject)["shortened_ranges"])
                assertEquals(excerpt.toByteArray(Charsets.UTF_8).size, (packet["selection_coverage"] as JsonObject).integer("selected_source_bytes"))
            } else {
                assertTrue((packet["context_omissions"] as JsonObject).integer("items") > 0)
            }
            assertTrue(excerpt.isNotBlank())
        }
    }

    @Test fun shortenedSourceKeepsTheRequestedLineAndItsExactNewRange() {
        val requested = JsonObject(source() + ("matched_line" to JsonPrimitive(27)))
        val fitted = render(receipt(requested), 450)
        assertTrue(fitted.source)
        val range = (data(fitted)["selected_ranges"] as JsonArray).single() as JsonObject
        assertEquals(27, range.integer("start_line"))
        assertEquals(JsonPrimitive(27), range["matched_line"])
        assertEquals(lines.drop(6).take(range.integer("end_line") - 26).joinToString("\n"), (range["excerpt"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(false), range["range_complete"])
    }

    @Test fun unfitSourceRemainsAnUnreadPointerAndTinyAllowancesRemainEmpty() {
        val oversizedLine = source(listOf("x".repeat(3000)))
        val fitted = render(receipt(oversizedLine), 450)
        assertFalse(fitted.source)
        assertTrue(fitted.candidates)
        val pointer = (data(fitted)["source_read_candidates"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(blobSha), pointer["blob_sha"])
        assertEquals(JsonPrimitive(21), pointer["start_line"])
        assertEquals(JsonPrimitive(21), pointer["end_line"])
        assertEquals(JsonPrimitive("REQUEST_CONTEXT_BUDGET"), pointer["deferred_reason"])
        for (budget in listOf(0, 1, 24, -1)) assertEquals("", render(receipt(), budget).text)
    }

    @Test fun anUpstreamPartialRequestedLineIsDeferredInsteadOfClaimedInEarlierSource() {
        val observed = (1..6).map { "prefix$it".padEnd(149, ' ') } + "requested ".padEnd(1300, 'x')
        assertEquals(2200, observed.joinToString("\n").toByteArray(Charsets.UTF_8).size)
        val range = JsonObject(source(observed) + mapOf("matched_line" to JsonPrimitive(27), "range_complete" to JsonPrimitive(false)))
        val fitted = render(receipt(range), 450)
        assertFalse(fitted.source)
        assertTrue(fitted.candidates)
        val packet = data(fitted)
        assertFalse("selected_ranges" in packet)
        val pointer = (packet["source_read_candidates"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(27), pointer["matched_line"])
        assertEquals(JsonPrimitive(blobSha), pointer["blob_sha"])
        assertEquals(JsonPrimitive("REQUEST_CONTEXT_BUDGET"), pointer["deferred_reason"])
    }

    @Test fun referenceSectionsUseCompleteLinesAndPreserveTheirBlob() {
        val document = JsonObject(source(lines, 101) + mapOf("path" to JsonPrimitive("AGENTS.md"), "section_complete" to JsonPrimitive(true)))
        val input = buildJsonObject {
            put("documents", JsonArray(listOf(document)))
            put("coverage", buildJsonObject { put("tree_complete", true) })
        }
        val fitted = render(input, 450)
        assertFalse(fitted.source)
        assertTrue(fitted.documents)
        val range = (data(fitted)["documents"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(blobSha), range["blob_sha"])
        assertEquals(lines.take(range.integer("end_line") - 100).joinToString("\n"), (range["excerpt"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(false), range["section_complete"])
        assertEquals(JsonPrimitive(false), range["range_complete"])
    }

    @Test fun fullAllowancePreservesThePreparedProjectionAndCompactWindow() {
        val range = source(listOf("fun decode() = source()"))
        val input = JsonObject((receipt() - "selected_ranges") + ("source_window" to range))
        val fitted = render(input, 4000)
        assertTrue(fitted.source)
        val packet = data(fitted)
        assertEquals(range, packet["source_window"])
        assertEquals(JsonPrimitive(true), packet["source_complete"])
        assertEquals(JsonPrimitive(false), packet["selection_truncated"])
        assertFalse("context_omissions" in packet)
    }

    @Test fun anUnreadSelectedRangeIsAPointerEvenWhenItFitsWithoutShrinking() {
        val pointer = JsonObject((source() - "excerpt") + ("read_required" to JsonPrimitive(true)))
        val fitted = render(buildJsonObject { put("selected_ranges", JsonArray(listOf(pointer))) }, 4000)
        assertFalse(fitted.source)
        assertTrue(fitted.candidates)
        assertEquals(pointer, (data(fitted)["selected_ranges"] as JsonArray).single())
        assertFalse("excerpt" in ((data(fitted)["selected_ranges"] as JsonArray).single() as JsonObject))
    }

    @Test fun shrinkingDoesNotCountUnreadPointerLinesAsDeliveredSource() {
        val pointer = JsonObject((source() - "excerpt") + ("read_required" to JsonPrimitive(true)))
        val input = JsonObject(
            receipt() + mapOf(
                "selected_ranges" to JsonArray(listOf(pointer)),
                "features" to JsonArray(listOf(JsonPrimitive("Optional feature detail ".repeat(600))))
            )
        )
        val fitted = render(input, 450)
        assertFalse(fitted.source)
        assertTrue(fitted.candidates)
        val packet = data(fitted)
        val coverage = packet["selection_coverage"] as JsonObject
        assertEquals(0, coverage.integer("selected_files"))
        assertEquals(0, coverage.integer("selected_source_bytes"))
        assertEquals(0, coverage.integer("selected_source_lines"))
        assertEquals(pointer, (packet["selected_ranges"] as JsonArray).single())
    }

    @Test fun deliveredSourceAndUnreadPointersKeepTheirSeparateCoverage() {
        val delivered = source(listOf("fun decode() = source()"))
        val pointer = JsonObject((source() - "excerpt") + mapOf("path" to JsonPrimitive("src/Unread.kt"), "read_required" to JsonPrimitive(true)))
        val input = JsonObject(
            receipt(delivered) + mapOf(
                "selected_ranges" to JsonArray(listOf(delivered, pointer)),
                "features" to JsonArray(listOf(JsonPrimitive("Optional feature detail ".repeat(600))))
            )
        )
        val fitted = render(input, 800)
        assertTrue(fitted.source)
        assertTrue(fitted.candidates)
        val packet = data(fitted)
        assertEquals(JsonArray(listOf(delivered, pointer)), packet["selected_ranges"])
        val coverage = packet["selection_coverage"] as JsonObject
        assertEquals(1, coverage.integer("selected_files"))
        assertEquals((delivered["excerpt"] as JsonPrimitive).content.toByteArray(Charsets.UTF_8).size, coverage.integer("selected_source_bytes"))
        assertEquals(1, coverage.integer("selected_source_lines"))
    }
}
