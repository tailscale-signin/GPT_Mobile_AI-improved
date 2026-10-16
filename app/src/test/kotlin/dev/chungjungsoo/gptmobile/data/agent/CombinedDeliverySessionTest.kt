package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDeliverySessionTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/combined/great-emu-war.json")).readText()).jsonObject
    private val sources = fixture.getValue("sources").jsonArray.map { row ->
        val value = row.jsonObject
        CombinedModelResponse(value.getValue("platformUid").jsonPrimitive.content, value.getValue("platformName").jsonPrimitive.content, content = value.getValue("content").jsonPrimitive.content)
    }
    private val winner = fixture.getValue("selectedAnswer").jsonPrimitive.content

    // A source-preserving test response, grouped by topic with exact duplicate sentences removed.
    private val complete = sources.groupBy { ConversationSubject.extract(it.content) }.entries.joinToString("\n\n") { (topic, rows) ->
        "## $topic\n\n" + rows.flatMap { CombinedContentCoverage.sentences(ConversationSubject.withoutMetadata(it.content)) }.distinct().joinToString(" ")
    }
    private fun session(text: String, terminal: ProviderEvent = ProviderEvent.Completed) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            assertTrue(tools.isEmpty())
            assertTrue(exchanges.isEmpty())
            emit(ProviderEvent.TextDelta(text))
            emit(terminal)
        }
    }

    @Test fun greatEmuWarWinnerFailsCoverageAgainstAllFiveFrozenInputs() {
        val coverage = CombinedContentCoverage(sources)
        val missing = coverage.check(winner)
        assertFalse(missing.accepted)
        assertTrue(missing.missing.flatMap { it.profileUids }.containsAll(listOf("profile-2", "profile-3", "profile-4", "profile-5")))
        assertTrue(coverage.check(complete).accepted)
        assertTrue(complete.contains("Commodore Perry"))
        assertTrue(complete.contains("constitutional government"))
        val before = fixture.getValue("before").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(117, sources[1].content.length - before.length)
    }

    @Test fun losingTopicsRepairsOnceWithoutPublishingTheWinningDraft() = runTest {
        var repairs = 0
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, missing ->
            repairs++
            assertTrue(missing.isNotEmpty())
            session(complete)
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals(complete, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun unproductiveRepairRetainsAllFrozenSourcesWithoutProfileLabels() = runTest {
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ -> session(winner) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(answer.startsWith(winner))
        assertTrue(answer.contains("## Further details"))
        assertFalse(answer.contains("**From "))
        assertFalse(answer.contains("individual AI profiles"))
        assertTrue(CombinedContentCoverage(sources).check(answer).accepted)
        assertTrue(events.any { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun revocationDuringRepairDoesNotPublishRetainedContent() = runTest {
        var allowed = true
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = { check(allowed) { "PERMISSION_REVOKED" } }) { _, _ ->
            allowed = false
            session(complete)
        }
        val emitted = mutableListOf<ProviderEvent>()
        val failure = runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { emitted += it } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(emitted.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
    }

    @Test fun cancellationAndDownstreamFailuresRemainTransparent() = runTest {
        val wrapper = CombinedDeliverySession(session(complete), sources, 100_000, validateAccess = {}) { _, _ -> error("No repair") }
        val consumerFailure = IllegalStateException("consumer stopped")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw consumerFailure } }.exceptionOrNull() === consumerFailure)
        val stop = CancellationException("stopped")
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).collect { throw stop } }.exceptionOrNull() === stop)
    }

    @Test fun failedRepairCanStillPreserveCompleteSourceCoverage() = runTest {
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ -> error("Context cannot fit repair") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(answer.startsWith(winner))
        assertTrue(CombinedContentCoverage(sources).check(answer).accepted)
        assertTrue(events.any { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun failedRepairStreamNeverPublishesItsBrokenDraft() = runTest {
        val broken = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.TextDelta("Incomplete replacement"))
                throw java.io.IOException("Stream disconnected")
            }
        }
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ -> broken }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(answer.startsWith(winner))
        assertFalse(answer.contains("Incomplete replacement"))
        assertTrue(CombinedContentCoverage(sources).check(answer).accepted)
        assertTrue(events.any { it == ProviderEvent.Completed })
    }

    @Test fun initialStreamFailureDeliversBufferedTextWithFailure() = runTest {
        val broken = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.TextDelta(winner))
                throw java.io.IOException("Stream disconnected")
            }
        }
        val wrapper = CombinedDeliverySession(broken, sources, 100_000, validateAccess = {}) { _, _ -> error("Must not repair failed acquisition") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(winner, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
    }

    @Test fun downstreamFailureDuringRepairRemainsTransparent() = runTest {
        val consumerFailure = IllegalStateException("consumer stopped")
        val repair = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.Notice("repair progress"))
                emit(ProviderEvent.TextDelta(complete))
                emit(ProviderEvent.Completed)
            }
        }
        val wrapper = CombinedDeliverySession(session(winner), sources, 100_000, validateAccess = {}) { _, _ -> repair }
        val failure = runCatching {
            wrapper.streamRound(emptyList(), emptyList()).collect {
                if (it is ProviderEvent.Notice && it.message == "repair progress") throw consumerFailure
            }
        }.exceptionOrNull()
        assertEquals(consumerFailure.javaClass, failure?.javaClass)
        assertEquals(consumerFailure.message, failure?.message)
        // Coroutine debug stack recovery can copy the exception across the repair timeout boundary.
        assertTrue(failure === consumerFailure || failure?.cause === consumerFailure)
    }

    @Test fun fallbackCannotRewriteAnAlreadyCommittedPrefix() = runTest {
        val prefix = "Committed prefix."
        val wrapper = CombinedDeliverySession(session(prefix + winner), sources, 100_000, committedPrefix = prefix, validateAccess = {}) { _, _ -> session(complete) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(answer.startsWith(prefix + winner))
        assertTrue(CombinedContentCoverage(sources).check(answer).accepted)
        assertTrue(events.any { it == ProviderEvent.Completed })
    }

    @Test fun impossibleCharacterAllowanceNeverSilentlyMarksIncompleteAsComplete() = runTest {
        val wrapper = CombinedDeliverySession(session(winner), sources, winner.length + 8, validateAccess = {}) { _, _ -> session(winner) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(winner, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(events.none { it == ProviderEvent.Completed })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.startsWith("COMBINED_COVERAGE_INCOMPLETE"))
    }

    @Test fun cultureExportMustNotDropTangChinaWhenSelectingAnAthensAnswer() {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/combined/culture-and-era.json")).readText()).jsonObject
        val rows = fixture.getValue("sources").jsonArray.map { item ->
            val row = item.jsonObject
            CombinedModelResponse(row.getValue("platformUid").jsonPrimitive.content, row.getValue("platformName").jsonPrimitive.content, content = row.getValue("content").jsonPrimitive.content)
        }
        val result = CombinedContentCoverage(rows).check(fixture.getValue("selectedAnswer").jsonPrimitive.content)
        assertFalse(result.accepted)
        assertTrue(result.missing.any { "profile-2" in it.profileUids && it.text.contains("Tang Dynasty") })
    }

    @Test fun suggestionQuestionsAreNotMandatoryFactsInCombinedAnswers() {
        val fact = "Athens used wealth from silver mines to fund public works and naval power."
        val content = fact + "\n\nNext questions:\n- **Another culture:** Would you like a different era?"
        val coverage = CombinedContentCoverage(listOf(CombinedModelResponse("one", "One", content = content)))
        assertTrue(coverage.check(fact).accepted)
        assertEquals(1, coverage.units.size)
    }

    @Test fun partialEditorialRepairRetainsItsImprovementAndAppendsRemainingTopic() = runTest {
        val rows = listOf(
            CombinedModelResponse("athens", "Athens", content = "Athens used wealth from silver mines to fund public works and naval power."),
            CombinedModelResponse("tang", "Tang", content = "Tang China welcomed Persian merchants and Japanese monks along the Silk Road."),
            CombinedModelResponse("egypt", "Egypt", content = "Egyptian scribes recorded tax payments and grain harvests using hieroglyphic writing.")
        )
        val draft = rows[0].content
        val improved = draft + "\n\n" + rows[1].content
        val wrapper = CombinedDeliverySession(session(draft), rows, 100_000, validateAccess = {}) { _, _ -> session(improved) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val answer = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(answer.startsWith(improved))
        assertTrue(answer.contains(rows[2].content))
        assertTrue(CombinedContentCoverage(rows).check(answer).accepted)
        assertTrue(events.any { it == ProviderEvent.Completed })
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test fun sharedTextNamesEverySubmitterAndParaphrasesStayNeutral() {
        val sentence = "The new government created a conscript army and built railways."
        val rows = listOf(CombinedModelResponse("one", "One", content = sentence), CombinedModelResponse("two", "Two", content = sentence), CombinedModelResponse("three", "Three", content = "Different information."))
        assertEquals(setOf("one", "two"), combinedVerbatimProfiles(sentence, rows))
        assertTrue(combinedVerbatimProfiles("Rail transport and national conscription were introduced.", rows).isEmpty())
        assertFalse(comprehensiveCombinedEnvelope("{}"))
        assertTrue(comprehensiveCombinedEnvelope("{\"combination_policy\":\"COMPREHENSIVE\"}"))
    }

    @Test fun claimAuditKeepsRepairingWhileUniqueCoverageMakesProgress() = runTest {
        val rows = listOf(
            CombinedModelResponse("a", "A", content = "Athens built naval ships from the proceeds of silver mines."),
            CombinedModelResponse("b", "B", content = "Tang merchants carried ceramics across the Silk Road."),
            CombinedModelResponse("c", "C", content = "Egyptian scribes measured annual grain taxes."),
            CombinedModelResponse("d", "D", content = "Roman engineers constructed aqueducts for distant towns.")
        )
        var rounds = 0
        val initial = rows[0].content
        val wrapper = CombinedDeliverySession(session(initial), rows, 100_000, validateAccess = {}) { draft, missing ->
            rounds++
            assertTrue(missing.isNotEmpty())
            assertTrue(draft.startsWith(initial))
            session(rows.take(rounds + 1).joinToString("\n\n") { it.content })
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, rounds)
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(rows.joinToString("\n\n") { it.content }, events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun shrinkingMissingCountCannotJustifyDroppingPreviouslyCoveredClaim() = runTest {
        val rows = listOf(
            CombinedModelResponse("a", "A", content = "Athens built naval ships from silver mines."),
            CombinedModelResponse("b", "B", content = "Tang merchants traded silk with Persian caravans."),
            CombinedModelResponse("c", "C", content = "Egyptian scribes recorded yearly grain taxes."),
            CombinedModelResponse("d", "D", content = "Roman engineers supplied distant towns with aqueducts.")
        )
        var rounds = 0
        val wrapper = CombinedDeliverySession(session(rows[0].content), rows, 100_000, validateAccess = {}) { _, _ ->
            rounds++
            if (rounds == 1) {
                session(rows.take(2).joinToString("\n\n") { it.content })
            } else {
                session(rows.drop(1).joinToString("\n\n") { it.content })
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, rounds)
        val delivered = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(delivered.startsWith(rows.take(2).joinToString("\n\n") { it.content }))
        assertTrue(delivered.contains("## Additional source details"))
        assertTrue(delivered.contains(rows[2].content))
        assertTrue(delivered.contains(rows[3].content))
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun oversizedRepairRetainsTheAuditedDraft() = runTest {
        val original = "Athens funded naval ships through silver mines."
        val missing = "Tang merchants traded silk with Persian caravans."
        val rows = listOf(CombinedModelResponse("a", "A", content = original), CombinedModelResponse("b", "B", content = missing))
        val wrapper = CombinedDeliverySession(session(original), rows, 150, validateAccess = {}) { _, _ -> session("x".repeat(151)) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val delivered = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(delivered.startsWith(original))
        assertFalse(delivered.contains("## Additional source details"))
        assertFalse(delivered.contains(rows[1].content))
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun toolAttemptDuringRepairCannotDropDraftOrExecuteAction() = runTest {
        val original = "Athens funded naval ships through silver mines."
        val rows = listOf(CombinedModelResponse("a", "A", content = original), CombinedModelResponse("b", "B", content = "Tang merchants traded silk with Persian caravans."))
        var afterTool = false
        val badRepair = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall("one", "unexpected", kotlinx.serialization.json.buildJsonObject {}))
                afterTool = true
                emit(ProviderEvent.Completed)
            }
        }
        val wrapper = CombinedDeliverySession(session(original), rows, 1000, validateAccess = {}) { _, _ -> badRepair }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertFalse(afterTool)
        assertTrue(events.none { it is ProviderEvent.ToolCall })
        val delivered = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(delivered.startsWith(original))
        assertTrue(delivered.contains("## Additional source details"))
        assertTrue(delivered.contains(rows[1].content))
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun rejectedRepairCannotReplaceCoverageTelemetry() = runTest {
        val rows = listOf(
            CombinedModelResponse("a", "A", content = "Athens funded naval ships through silver mines."),
            CombinedModelResponse("b", "B", content = "Tang merchants traded silk with Persian caravans."),
            CombinedModelResponse("c", "C", content = "Egyptian scribes recorded yearly grain taxes.")
        )
        val reported = mutableListOf<Pair<CombinedContentCoverage.Result, Boolean>>()
        val wrapper = CombinedDeliverySession(session(rows.first().content), rows, 2000, validateAccess = {}, onCoverage = { result, repaired -> reported += result to repaired }) { _, _ ->
            session(rows.drop(1).joinToString(" ") { it.content })
        }
        wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, reported.size)
        assertEquals(setOf("b", "c"), reported.first().first.missing.flatMap { it.profileUids }.toSet())
        assertFalse(reported.first().second)
        assertTrue(reported.last().first.accepted)
        assertTrue(reported.last().second)
    }

    @Test fun outputLimitedRepairContinuesBeforeCoverageIsPublished() = runTest {
        val original = "Athens funded naval ships through silver mines."
        val added = "Tang merchants traded silk with Persian caravans."
        val rows = listOf(CombinedModelResponse("a", "A", content = original), CombinedModelResponse("b", "B", content = added))
        val limited = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.isEmpty())
                emit(ProviderEvent.TextDelta(original))
                emit(ProviderEvent.Usage(outputTokens = 100, finishReason = "length"))
                emit(ProviderEvent.Completed)
            }
        }
        var continuations = 0
        val wrapper = CombinedDeliverySession(session(original), rows, 1000, validateAccess = {}) { _, _ ->
            OutputLimitRecoverySession(limited, maxContinuations = 2, allowInternalToolRecovery = true) { partial, exchanges ->
                continuations++
                assertEquals(original, partial)
                assertTrue(exchanges.isEmpty())
                session("\n\n$added")
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continuations)
        assertEquals("$original\n\n$added", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, events.last())
    }
}
