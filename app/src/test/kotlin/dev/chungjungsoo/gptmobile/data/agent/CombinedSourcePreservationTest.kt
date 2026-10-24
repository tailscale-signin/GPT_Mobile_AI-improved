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
