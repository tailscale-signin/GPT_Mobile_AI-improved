package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSourcePreservationTest {
    @Test fun mappedEditorialAssemblyRetainsEveryDistinctClaimButAccountsForRepetitionAndControlText() {
        val fact = "Portugal became a kingdom in 1139."
        val correction = "Actually, the earlier statement needs the qualification that recognition followed in 1143."
        val raw = "$fact\n\nLet me rewrite that.\n\n$fact\n\n$correction"
        val source = CombinedModelResponse("a", "A", content = raw, claimExtractionVersion = 3)
        val result = CombinedSourcePreservation.assemble("An overview.", listOf(source))
        assertTrue(result.text.startsWith("An overview."))
        assertEquals(1, Regex(Regex.escape(fact)).findAll(result.text).count())
        assertTrue(result.text.contains(correction))
        assertFalse(result.text.contains("Let me rewrite that"))
        assertEquals(2, result.fragments)
        assertEquals(raw, source.content)
        assertEquals(1, result.dispositions.count { it.disposition == "OMITTED_SELF_EDITING" && it.finalStart == it.finalEnd })
        val duplicate = result.dispositions.single { it.disposition == "MERGED_DUPLICATE" }
        assertEquals(fact, result.text.substring(duplicate.finalStart, duplicate.finalEnd))
        assertEquals(fact, raw.substring(duplicate.sourceStart, duplicate.sourceEnd))
        assertTrue(duplicate.finalLinks.isNotEmpty())
        val legacy = CombinedSourcePreservation.assemble("", listOf(source.copy(claimExtractionVersion = 2)))
        assertTrue(legacy.text.contains("Let me rewrite that."))
        assertEquals(2, Regex(Regex.escape(fact)).findAll(legacy.text).count())
    }

    @Test fun attributedHistoricalFragmentsGroupByPeriodWithoutChangingTheCommittedOverview() {
        val sources = listOf(
            CombinedModelResponse("a", "A", content = "Portugal became a kingdom in 1139.\n\nPortugal adopted the euro in 1999.", claimExtractionVersion = 3),
            CombinedModelResponse("b", "B", content = "Portugal restored independence in 1640.\n\nPortugal received papal recognition in 1179.", claimExtractionVersion = 3)
        )
        val prefix = "## Overview\n\nA retained opening paragraph."
        val result = CombinedSourcePreservation.assemble(prefix, sources, "Explain the history of Portugal")
        assertTrue(result.text.startsWith(prefix))
        assertEquals(1, Regex("### Medieval developments").findAll(result.text).count())
        assertTrue(result.text.indexOf("1179") < result.text.indexOf("### Early modern transformation"))
        assertTrue(result.text.indexOf("1640") < result.text.indexOf("### Recent history and present"))
        result.dispositions.forEach { d ->
            val source = sources.single { it.platformUid == d.profileUid }
            assertEquals(source.content.substring(d.sourceStart, d.sourceEnd), result.text.substring(d.finalStart, d.finalEnd))
        }
        assertTrue(result.passages.all { it.profileUids.size == 1 })
    }

    @Test fun missingSentenceRetainsOnlyItsIndependentUnitWithoutRepeatingTheMappedSentence() {
        val included = "Portugal joined NATO in 1949 and the EEC in 1986."
        val omitted = "Portugal restored independence from Spain in 1640."
        val source = CombinedModelResponse("a", "A", content = "$included $omitted", claimExtractionVersion = 2)
        val draft = "Portugal joined the EEC in 1986 and NATO in 1949."
        val result = CombinedSourcePreservation.assemble(draft, listOf(source))
        assertEquals(1, result.fragments)
        assertTrue(result.text.startsWith(draft))
        assertTrue(result.text.contains(omitted))
        assertFalse(result.text.contains(included))
        assertEquals(listOf("PARAPHRASED", "RETAINED_FRAGMENT"), result.dispositions.map { it.disposition })
        val retained = result.dispositions.last()
        assertEquals(omitted, source.content.substring(retained.sourceStart, retained.sourceEnd))
        assertEquals(omitted, result.text.substring(retained.finalStart, retained.finalEnd))
    }

    @Test fun adjacentUncertaintyCannotBeDetachedFromItsPositiveClaim() {
        val qualified = "The treatment improves symptoms. However, the evidence remains limited."
        val other = "The study recruited 12 adults in 2020."
        val source = CombinedModelResponse("a", "A", content = "$qualified $other", claimExtractionVersion = 2)
        val result = CombinedSourcePreservation.assemble("The treatment improves symptoms. $other", listOf(source))
        assertEquals(1, result.fragments)
        assertTrue(result.text.contains(qualified))
        val retained = result.dispositions.single { it.disposition == "RETAINED_FRAGMENT" }
        assertEquals(qualified, source.content.substring(retained.sourceStart, retained.sourceEnd))
        assertEquals(qualified, result.text.substring(retained.finalStart, retained.finalEnd))
    }

    @Test fun completeParaphraseUsesClaimAccountingWithoutDuplicatingTheSource() {
        val sources = listOf(CombinedModelResponse("a", "A", content = "Portugal joined NATO in 1949 and the EEC in 1986."))
        val draft = "Portugal joined the EEC in 1986 and NATO in 1949."
        val result = CombinedSourcePreservation.assemble(draft, sources)
        assertEquals(draft, result.text)
        assertEquals(0, result.fragments)
        assertEquals("PARAPHRASED", result.dispositions.single().disposition)
        assertTrue(result.dispositions.single().finalLinks.isNotEmpty())
        assertFalse(result.text.contains("Retained source details"))
    }

    @Test fun missingQualificationAndSwappedEventDatesStillRetainTheOriginal() {
        val source = CombinedModelResponse("a", "A", content = "Portugal joined NATO in 1949 and the EEC in 1986.")
        assertEquals(1, CombinedSourcePreservation.assemble("Portugal joined NATO in 1986 and the EEC in 1949.", listOf(source)).fragments)
        val qualified = source.copy(content = "The treatment may improve symptoms in adults only.")
        assertEquals(1, CombinedSourcePreservation.assemble("The treatment improves symptoms.", listOf(qualified)).fragments)
    }

    @Test fun sourcePreservingFinalIsStagedAndFailedWriterKeepsEverySourceBlock() = runTest {
        val source = CombinedModelResponse("a", "A", content = "The estimate is 42 ± 3.\n\nA qualification applies only in winter.")
        var displayed = false
        val base = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("An overview."))
                assertFalse(displayed)
                emit(ProviderEvent.Failed("Connection lost"))
                assertFalse(displayed)
            }
        }
        val session = CombinedDeliverySession(base, listOf(source), 10000, preserveSourceContent = true, validateAccess = {}, maxRepairRounds = 0, originalRequest = "Explain the findings") { _, _ -> error("No replay") }
        val events = mutableListOf<ProviderEvent>()
        session.streamRound(emptyList(), emptyList()).collect {
            events += it
            if (it is ProviderEvent.TextDelta) displayed = true
        }
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        val report = events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report
        assertTrue(text.startsWith("An overview."))
        assertEquals(2, report.sourceDispositions.size)
        report.sourceDispositions.forEach { d ->
            assertEquals(source.content.substring(d.sourceStart, d.sourceEnd), text.substring(d.finalStart, d.finalEnd))
        }
        assertFalse(report.protocolComplete)
        assertEquals("UNVERIFIED", report.verificationStatus)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun retainedBlocksPreserveIndentedCodeAndMarkdownHardBreaks() {
        val code = "    val a = 1\n    println(a)  "
        val prose = "First line  \nSecond line  "
        val source = "\n" + code + "\n\n" + prose + "\n\n"
        val blocks = CombinedSourcePreservation.blocks(source)
        assertEquals(listOf(code, prose), blocks.map { it.text })
        blocks.forEach { assertEquals(it.text, source.substring(it.start, it.end)) }
    }

    @Test fun accountsForEveryParagraphBeyondTheFormer72UnitAuditLimit() {
        val content = (1..101).joinToString("\n\n") { "Unique item $it has value ${it * 7}, with exception ${it + 3}." }
        val sources = listOf(CombinedModelResponse("a", "A", content = content))
        val result = CombinedSourcePreservation.assemble("An overview.", sources)
        assertEquals(101, result.dispositions.size)
        assertEquals(101, result.fragments)
        result.dispositions.forEach { d -> assertEquals(content.substring(d.sourceStart, d.sourceEnd), result.text.substring(d.finalStart, d.finalEnd)) }
        assertTrue(result.text.contains("Unique item 101"))
    }

    @Test fun preservesCodeTablesUnicodeAndConflictingNumbersWithoutInventingConsensus() {
        val left = "# Café 🌍\n\nThe estimate is 42 ± 3.\n\n| Key | Value |\n|---|---|\n| α | β |\n\n```kotlin\nval x = 2\n\nprintln(x)\n```"
        val right = "The estimate is 50 ± 5."
        val sources = listOf(CombinedModelResponse("a", "A", content = left), CombinedModelResponse("b", "B", content = right))
        val result = CombinedSourcePreservation.assemble("", sources)
        assertEquals(5, result.dispositions.size)
        result.dispositions.forEach { d ->
            val source = sources.single { it.platformUid == d.profileUid }
            assertEquals(source.content.substring(d.sourceStart, d.sourceEnd), result.text.substring(d.finalStart, d.finalEnd))
        }
        assertTrue(result.text.contains("42 ± 3"))
        assertTrue(result.text.contains("50 ± 5"))
        assertTrue(result.passages.all { it.profileUids.size == 1 })
    }

    @Test fun identicalCompleteSourcesHaveSharedOwnershipButAmbiguousMatchingSentencesDoNotMerge() {
        val a = CombinedModelResponse("a", "A", content = "France\n\nIt has a population of 10.")
        val b = CombinedModelResponse("b", "B", content = "Italy\n\nIt has a population of 10.")
        val result = CombinedSourcePreservation.assemble("", listOf(a, b, a.copy(platformUid = "c", platformName = "C")))
        assertEquals(4, result.fragments)
        assertEquals(6, result.dispositions.size)
        assertEquals(setOf("a", "c"), result.passages.first().profileUids)
        assertEquals(2, result.dispositions.count { it.disposition == "MERGED_DUPLICATE" })
        val draft = CombinedSourcePreservation.assemble(a.content, listOf(a))
        assertEquals(0, draft.fragments)
        assertEquals(a.content, draft.text)
    }
}
