package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultMergerTest {
    private val text = "The city council approved a new transit service expansion with frequent buses and reliable connections across all neighbourhoods beginning this autumn."
    private fun candidate(owner: Int, path: String, snippet: String = "", title: String = "Canadian transit service expansion", rank: Int = 1) = requireNotNull(
        SearchCandidate.create(
            buildJsonObject {
                put("url", "https://example.org/$path")
                put("title", title)
                put("snippet", snippet)
            },
            "selection-$owner",
            owner,
            "engine-$owner",
            rank
        )
    )
    private fun paths(result: SearchMergeResult) = result.sources.map { (it["url"] as JsonPrimitive).content.substringAfterLast('/') }

    @Test fun roundRobinHandlesUnevenEmptyAndEmptyOutputInputs() {
        assertEquals(listOf("A1", "B1", "C1", "A2", "C2", "A3"), roundRobin(listOf(listOf("A1", "A2", "A3"), listOf("B1"), listOf("C1", "C2"))))
        assertEquals(listOf(1, 2), roundRobin(listOf(emptyList(), listOf(1, 2), emptyList())))
        assertTrue(roundRobin(emptyList<List<Int>>()).isEmpty())
    }

    @Test fun duplicateHeavyBucketsStillContributeTheirLaterDistinctSources() {
        val buckets = listOf(listOf(candidate(0, "shared"), candidate(0, "A2")), listOf(candidate(1, "shared"), candidate(1, "B2")), listOf(candidate(2, "C1"), candidate(2, "C2")))
        val result = SearchResultMerger.merge(buckets, 10, 4, SearchMergePolicy())
        assertEquals(listOf("shared", "B2", "C1", "A2"), paths(result))
        assertEquals(JsonArray(listOf(JsonPrimitive("engine-0"), JsonPrimitive("engine-1"))), result.sources.first()["engines"])
        assertEquals(1, result.urlDuplicates)
    }

    @Test fun fourFullEnginesGetFiveEachAtTwentyRepresentatives() {
        val result = SearchResultMerger.merge((0..3).map { owner -> (1..10).map { candidate(owner, "$owner-$it", rank = it) } }, 10, 20, SearchMergePolicy())
        assertEquals(mapOf(0 to 5, 1 to 5, 2 to 5, 3 to 5), result.contributions)
        assertEquals(40, result.candidateCount)
        assertEquals(0, result.urlDuplicates)
    }

    @Test fun provenanceFromLateDuplicatesSurvivesTrimmingAndConnectorQuotas() {
        val first = candidate(0, "shared")
        val nested = candidate(1, "shared").copy(
            source = buildJsonObject {
                put("url", "https://example.org/shared")
                put("engine", "Reported provider")
                put("engines", JsonArray(listOf(JsonPrimitive("Nested one"), JsonPrimitive("Nested two"))))
            }
        )
        val result = SearchResultMerger.merge(listOf(listOf(first), listOf(candidate(1, "other"), nested)), 1, 1, SearchMergePolicy())
        assertTrue((result.sources.single()["engines"] as JsonArray).contains(JsonPrimitive("Reported provider")))
        assertEquals(mapOf(0 to 1), result.contributions)
        assertEquals(3, result.candidateCount)
    }

    @Test fun contentAliasesStayLocalAndCannotReappearAsNewRepresentatives() {
        val result = SearchResultMerger.merge(listOf(listOf(candidate(0, "a", text)), listOf(candidate(1, "b", text), candidate(1, "b", "Different text"))), 10, 20, SearchMergePolicy(contentMode = SearchContentMode.ENABLED))
        assertEquals(listOf("a"), paths(result))
        assertEquals(1, result.contentDuplicates)
        assertEquals(1, result.urlDuplicates)
        assertFalse(result.sources.single().containsKey("similarSources"))
        assertEquals(2, (result.retainedSources.single()["similarSources"] as JsonArray).size)
    }

    @Test fun shadowModeRetainsBothSourcesAndRollbackIsIndependentOfUrlGrouping() {
        val buckets = listOf(listOf(candidate(0, "a", text)), listOf(candidate(1, "b", text)))
        val shadow = SearchResultMerger.merge(buckets, 10, 20, SearchMergePolicy())
        assertEquals(2, shadow.sources.size)
        assertEquals(1, shadow.shadowContentDuplicates)
        assertEquals(0, shadow.contentDuplicates)
        assertEquals(2, SearchResultMerger.merge(buckets, 10, 20, SearchMergePolicy(contentMode = SearchContentMode.OFF)).sources.size)
        val duplicates = listOf(listOf(candidate(0, "a")), listOf(candidate(1, "a")))
        assertEquals(2, SearchResultMerger.merge(duplicates, 10, 20, SearchMergePolicy(dedupeUrls = false, contentMode = SearchContentMode.OFF)).sources.size)
    }

    @Test fun approximateMatchesNeverChainThroughAliases() {
        fun synthetic(path: String, owner: Int, shingles: Set<String>) = candidate(owner, path, text).let { it.copy(fingerprint = it.fingerprint.copy(shingles = shingles)) }
        val a = synthetic("a", 0, (1..10).map(Int::toString).toSet())
        val b = synthetic("b", 1, (1..9).map(Int::toString).toSet() + "11")
        val c = synthetic("c", 2, (1..8).map(Int::toString).toSet() + setOf("11", "12"))
        val result = SearchResultMerger.merge(listOf(listOf(a), listOf(b), listOf(c)), 10, 20, SearchMergePolicy(contentMode = SearchContentMode.ENABLED))
        assertEquals(listOf("a", "c"), paths(result))
    }

    @Test fun workGuardFallsBackToUrlGroupingWithoutDroppingEvidence() {
        val result = SearchResultMerger.merge(listOf(listOf(candidate(0, "a", text)), listOf(candidate(1, "b", text), candidate(1, "a", text))), 10, 20, SearchMergePolicy(contentMode = SearchContentMode.ENABLED, maxContentComparisons = 0))
        assertEquals(2, result.sources.size)
        assertEquals(1, result.urlDuplicates)
        assertTrue(result.degradedContentChecking)
    }
}
