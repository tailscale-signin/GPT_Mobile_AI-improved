package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedContextBudgetTest {
    private val read = AgentToolDefinition("read_recovery_context", "Read retained task evidence", buildJsonObject {})
    private fun turn(user: String, answer: String? = null, current: Boolean = false) =
        ConversationTurn(MessageV2(content = user, platformType = null), answer?.let { MessageV2(content = it, platformType = null) }, current)

    @Test fun cloudAndLocalPlansReserveReplayBeforeAdmittingOptionalHistory() {
        for (capacity in listOf(4096, 5632, 8192)) {
            val settings = TokenBudgetSettings(contextTokens = capacity, outputTokens = 512)
            val history = (1..40).map { turn("Inspect source $it", "Observed immutable evidence. ".repeat(20)) }
            val current = turn("Continue the selected review", current = true)
            val plan = ContextBudgetService.plan(history + current, "Preserve the selected SHA and user constraints.", listOf(read), settings, reserveEstimatedSafety = true)
            val allocated = ReplayAllowance.allocate(capacity, plan.promptTokens, 512, 4096)
            assertTrue("capacity=$capacity replay=${allocated.replay}", allocated.replay >= plan.toolResultBytes / 3)
            // The four captured compacted identity sets must fit without dropping a pair.
            for (required in listOf(108, 136, 155, 86)) assertTrue(allocated.replay >= required)
            assertEquals(listOf(read), plan.tools)
            assertEquals(current, plan.turns.last())
            assertTrue(allocated.input.toLong() + allocated.output + allocated.replay + allocated.safety <= capacity)
        }
    }

    @Test fun adaptivePreparationUsesTheSmallerTargetBeforeOptionalSectionsAreSelected() {
        val configured = TokenBudgetSettings(contextTokens = 1_048_576, outputTokens = 512)
        val planned = EngineeringAdaptivePolicy.budgets(EngineeringProfileSettings(mode = EngineeringMode.ADAPTIVE), "GitHub list branches", configured)
        assertEquals(8192, planned.contextTokens)
        val current = turn("List the selected branches", current = true)
        val plan = ContextBudgetService.plan(listOf(current), "User project rules.", listOf(read), planned, reserveEstimatedSafety = true)
        val available = PreparedContextBudget.optionalTokens(plan, planned.contextTokens, planned.outputTokens)
        val tooLarge = "Repository data ".repeat(2500)
        val small = "Source SHA: abc123. Coverage: one inspected file."
        val selected = PreparedContextBudget.sections(listOf(tooLarge, small), available)
        assertFalse(selected.contains("Repository data"))
        assertEquals("\n$small", selected)
        assertTrue(plan.promptTokens + ContextBudgetService.estimate(selected) + plan.toolResultBytes / 3 <= RequestAdmission.maximumEstimatedPrompt(8192L - 512))
        assertEquals(1_048_576, configured.contextTokens)
    }

    @Test fun optionalEvidenceKeepsWholeSectionsAndDoesNotDisplaceAFullBudget() {
        val full = ContextPlan(emptyList(), "Required instructions", listOf(read), 512 * 3, 512, "", promptTokens = 3000)
        assertEquals(0, PreparedContextBudget.optionalTokens(full, 4096, 512))
        assertEquals("", PreparedContextBudget.sections(listOf("{\"source_sha\":\"abc\",\"body\":\"required identity\"}"), 1))
        assertEquals("", PreparedContextBudget.sections(listOf("Optional"), 0))
    }

    @Test fun followUpRetainsPreviousRequestAndLabelledResponseExcerptBeforeOptionalSchemas() {
        val previous = turn("Review commit 0e625e250117475446b8f216df2501ddbef168f2; preserve explicit OFF.", "Selected SHA 0e625e25.\n" + "Long verified report 🧭. ".repeat(4000) + "\nOutstanding: inspect the next source page.")
        val original = previous.assistantMessage!!.content
        val current = turn("Summarize those problems and solutions", current = true)
        val optional = AgentToolDefinition("large_optional_schema", "Extra schema ".repeat(500), buildJsonObject {})
        val plan = ContextBudgetService.plan(
            listOf(previous, current),
            "Selected repository and SHA remain bound.",
            listOf(read, optional),
            TokenBudgetSettings(contextTokens = 4096, outputTokens = 768),
            reserveEstimatedSafety = true,
            continuityRequiredSchemas = setOf(read.name)
        )
        assertEquals(2, plan.turns.size)
        val retained = plan.turns.first()
        assertSame(previous.userMessage, retained.userMessage)
        val excerpt = retained.assistantMessage!!.content
        assertTrue(excerpt.startsWith("[Partial excerpt"))
        assertTrue(excerpt.contains("Selected SHA 0e625e25"))
        assertTrue(excerpt.contains("Outstanding: inspect the next source page"))
        assertFalse(excerpt.contains('\uFFFD'))
        assertEquals(original, previous.assistantMessage!!.content)
        assertTrue(plan.tools.contains(read))
        assertTrue(plan.notice.contains("partial excerpt"))
        val allocation = ReplayAllowance.allocate(4096, plan.promptTokens, 768, 4096)
        assertTrue(allocation.replay >= 512)
    }

    @Test fun continuityNeverShortensTheUsersPreviousRequest() {
        val previous = turn("Exact user constraints ".repeat(1000), "Prior answer")
        assertEquals(null, MemoryTokenWindow.continuityTurn(listOf(previous), 256, "Summarize those solutions") { ContextBudgetService.breakdown(listOf(it), "", emptyList()).total })
    }

    @Test fun requiredCurrentInputStillRejectsWithoutBeingClipped() {
        val current = turn("Required current request ".repeat(1000), current = true)
        assertThrows(RequestCapacityException::class.java) {
            ContextBudgetService.plan(listOf(current), "User rules", listOf(read), TokenBudgetSettings(contextTokens = 4096, outputTokens = 512), reserveEstimatedSafety = true)
        }
        assertEquals("Required current request ".repeat(1000), current.userMessage.content)
    }

    @Test fun optionalReferenceUsesResidualSpaceAfterHistoryToolOutputAndExactReplay() {
        val plan = ContextPlan(emptyList(), "", emptyList(), 1536, 512, "", promptTokens = 3400)
        val allowance = PreparedContextBudget.optionalTokens(plan, 5632, 4096)
        val fits = "source: pinned blob and line range"
        val tooLarge = "x".repeat(allowance * 3 + 1)
        val selected = PreparedContextBudget.sections(listOf(tooLarge, fits), allowance)
        assertTrue(fits in selected)
        assertFalse(tooLarge in selected)
        val prompt = plan.promptTokens.toLong() + plan.toolResultBytes / 3 + allowance + 64
        assertTrue(prompt + RequestAdmission.estimateSafety(prompt) + 512 <= 5632)
        assertEquals(0, PreparedContextBudget.optionalTokens(plan.copy(promptTokens = 8914), 8192, 512))
        assertEquals("", PreparedContextBudget.sections(listOf(fits), 0))
    }

    @Test fun repositoryPreparationCapturesMemoryBeforeSourceWithoutLettingHistoryFillItsWindow() = runTest {
        for (available in listOf(256, 330, 800, 4000)) {
            for (memoryFits in listOf(true, false)) {
                val calls = mutableListOf<String>()
                var memoryAllowance = -1
                val memory = if (memoryFits) "Saved user decision: retain the parser behavior." else "Historical observation ".repeat(400)
                val source = "Pinned source SHA and bounded lines."
                val prepared = PreparedContextBudget.repositorySections(
                    available,
                    prepareMemory = { allowance ->
                        calls += "capture latest request and decisions"
                        memoryAllowance = allowance
                        assertTrue(allowance <= available / 4)
                        memory
                    },
                    prepareSource = { allowance ->
                        calls += "prepare pinned source"
                        assertTrue(allowance >= available - available / 4)
                        assertTrue(allowance >= 256)
                        val retainedMemory = if (ContextBudgetService.estimate("\n$memory") <= memoryAllowance) memory else ""
                        assertEquals(available - if (retainedMemory.isEmpty()) 0 else ContextBudgetService.estimate("\n$retainedMemory"), allowance)
                        source
                    }
                )
                assertEquals(listOf("capture latest request and decisions", "prepare pinned source"), calls)
                assertEquals(source, prepared.source)
                assertEquals(if (ContextBudgetService.estimate("\n$memory") <= memoryAllowance) memory else "", prepared.memory)
                assertTrue(ContextBudgetService.estimate(prepared.text) <= available)
            }
        }
    }

    @Test fun exhaustedOptionalBudgetDoesNotCaptureMemoryOrLookUpSource() = runTest {
        for (available in listOf(0, 1, 255)) {
            val result = PreparedContextBudget.repositorySections(
                available,
                prepareMemory = { error("No memory preparation may run without an admitted optional window") },
                prepareSource = { error("No source/cache lookup may run without an admitted optional window") }
            )
            assertEquals(PreparedContextBudget.RepositorySections("", "", ""), result)
        }
    }
}
