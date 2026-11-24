package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPublicProgressTest {
    private val model = OutputLineage(OutputOrigin.MODEL_TEXT, invocationId = "primary")

    @Test fun completedBlockSurvivesAnUnfinishedBlockInTheSameChunk() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        val answer = progress.accept(ProviderEvent.TextDelta("<progress_update>The failing step is compilation.</progress_update><progress_update>Unfinished update", model))

        assertEquals("", answer)
        assertEquals(listOf("The failing step is compilation." to model), published)
        assertEquals("", progress.finish())
        assertEquals(1, published.size)
    }

    @Test fun eachCompletedBlockIsPublishedSeparatelyAndAnswerTextIsPreserved() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        val answer = progress.accept(ProviderEvent.TextDelta("Before.<progress_update>First finding.</progress_update>Between.<progress_update>Second finding.</progress_update>After.", model))

        assertEquals("Before.Between.After.", answer)
        assertEquals(listOf("First finding." to model, "Second finding." to model), published)
        assertEquals("", progress.finish())
    }

    @Test fun splitClosingAndOpeningMarkersKeepIndependentBlockBoundaries() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        val chunks = listOf(
            "<progress_up",
            "date>First finding.</progress_up",
            "date><progress_up",
            "date>Second finding.</progress_",
            "update>Final answer."
        )

        val answer = StringBuilder()
        for (chunk in chunks) answer.append(progress.accept(ProviderEvent.TextDelta(chunk, model)))

        assertEquals("Final answer.", answer.toString())
        assertEquals(listOf("First finding." to model, "Second finding." to model), published)
    }

    @Test fun mixedOriginsOrInvocationsCannotPublishACompletedBlock() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        val application = OutputLineage(OutputOrigin.APPLICATION_REPORT)
        progress.accept(ProviderEvent.TextDelta("<progress_update>Unverified owner", model))
        progress.accept(ProviderEvent.TextDelta("</progress_update>", application))
        progress.accept(ProviderEvent.TextDelta("<progress_update>Another mixed owner", model))
        progress.accept(ProviderEvent.TextDelta("</progress_update>", model.copy(invocationId = "other")))
        progress.accept(ProviderEvent.TextDelta("<progress_update>Application report.</progress_update>", application))
        progress.accept(ProviderEvent.TextDelta("<progress_update>Unknown report.</progress_update>"))

        assertTrue(published.isEmpty())
        progress.accept(ProviderEvent.TextDelta("<progress_update>A separately owned finding.</progress_update>", model))
        assertEquals(listOf("A separately owned finding." to model), published)
    }

    @Test fun aSplitOpeningMarkerRetainsItsOriginalOwnerUntilItCompletes() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        progress.accept(ProviderEvent.TextDelta("<progress_up", OutputLineage(OutputOrigin.APPLICATION_REPORT)))
        progress.accept(ProviderEvent.TextDelta("date>Mixed marker.</progress_update><progress_update>Independent finding.</progress_update>", model))

        assertEquals(listOf("Independent finding." to model), published)
    }

    @Test fun aMarkerThatBecameAnswerTextCannotContaminateALaterCompleteBlock() = runBlocking {
        val published = mutableListOf<Pair<String, OutputLineage>>()
        val progress = GitHubPublicProgress { text, lineage -> published += text to lineage }
        assertEquals("", progress.accept(ProviderEvent.TextDelta("<progress_up", OutputLineage(OutputOrigin.APPLICATION_REPORT))))
        val answer = progress.accept(ProviderEvent.TextDelta("ended text.<progress_update>New finding.</progress_update>", model))

        assertEquals("<progress_upended text.", answer)
        assertEquals(listOf("New finding." to model), published)
    }

    @Test fun eachBlockKeepsItsOwnBoundAndUnclosedTextNeverPublishes() = runBlocking {
        val published = mutableListOf<String>()
        val progress = GitHubPublicProgress { text, _ -> published += text }
        progress.accept(ProviderEvent.TextDelta("<progress_update>${"a".repeat(3000)}</progress_update><progress_update>Small finding.</progress_update><progress_update>Never closed", model))
        progress.finish()

        assertEquals(listOf("a".repeat(2400), "Small finding."), published)
    }

    @Test fun legacyParserConsumersKeepTheSameTextSegmentsWithoutBoundarySentinels() {
        val parser = PublicProgressParser()
        assertEquals(
            listOf(false to "Before.", true to "First.", true to "Second.", false to "After."),
            parser.accept("Before.<progress_update>First.</progress_update><progress_update></progress_update><progress_update>Second.</progress_update>After.")
        )
        assertEquals(emptyList<Pair<Boolean, String>>(), parser.accept("", flush = true))
    }
}
