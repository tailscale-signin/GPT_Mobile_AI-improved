package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Incident shapes only: all identities, queries and source text are synthetic. */
class SearchIncidentRegressionTest {
    @Test fun compactedMarkdownLeadsSurviveRedirectNormalizationAndFalseLegacyFlag() {
        val detail = """
            # Search Results
            ### 1. Synthetic source A
            **URL**: //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa&rut=synthetic
            **Snippet**: A short useful fact.
            ### 2. Synthetic source B
            **URL**: https://example.org/b
            **Snippet**: A contrary fact.
            [Compacted evidence; full result retained.]
        """.trimIndent()
        val result = buildJsonObject {
            put("results", JsonArray(emptyList()))
            put(
                "engines",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("status", "unavailable")
                            put("detail", "URL: https://provider.example/credentials")
                        },
                        buildJsonObject {
                            put("status", "completed")
                            put("detail", detail)
                            put("truncated", false)
                        }
                    )
                )
            )
        }
        val sources = extractSearchSources(result)
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), sources.map { it.getValue("url").jsonPrimitive.content })
        assertTrue(sources.all { it["partial"] == JsonPrimitive(true) })
        assertTrue(evidenceHasTruncation(result))
        assertFalse(sources.any { it.toString().contains("credentials") })
    }

    @Test fun checkpointRetainsAllFourteenSearchRowsAndFivePages() {
        fun row(i: Int) = buildJsonObject {
            put("url", "https://example.org/source/$i")
            put("title", "Source $i")
            put("snippet", "Exact synthetic evidence $i")
        }
        val payload = buildJsonObject {
            put("results", JsonArray((1..14).map(::row)))
            put("pages", buildJsonObject { put("results", JsonArray((15..19).map(::row))) })
        }
        val checkpoint = buildJsonObject {
            put("payload_type", "JSON")
            put("payload", payload.toString())
            put("display", "Bounded preview")
        }
        assertEquals(19, extractSearchSources(checkpoint).size)
        assertEquals(payload, searchProviderPayload(checkpoint))
    }

    @Test fun redirectRecognitionIsBoundedAndNeverAcceptsUnsafeDestinations() {
        assertNull(resolveSearchRedirect("//unknown.example/l/?uddg=https%3A%2F%2Fexample.org"))
        assertNull(resolveSearchRedirect("https://duckduckgo.com/l/?uddg=javascript%3Aalert%281%29"))
        assertNull(resolveSearchRedirect("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fuser%3Apass%40example.org"))
        assertEquals("https://example.org/a?x=1", resolveSearchRedirect("https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fa%3Fx%3D1"))
    }

    @Test fun deliveryLimitDoesNotDeleteRetainedCandidates() {
        val bucket = (1..20).map { index ->
            requireNotNull(
                SearchCandidate.create(
                    buildJsonObject {
                        put("url", "https://example.org/$index")
                        put("title", "Title $index")
                        put("snippet", "Evidence $index")
                    },
                    "engine",
                    0,
                    "Synthetic",
                    index
                )
            )
        }
        val merged = SearchResultMerger.merge(listOf(bucket), 5, 5, SearchMergePolicy(maxCandidates = 8, contentMode = SearchContentMode.OFF))
        assertEquals(5, merged.sources.size)
        assertEquals(20, merged.retainedSources.size)
        assertEquals(20, merged.candidateCount)
    }

    @Test fun evidenceFailuresDoNotBecomeSuccessfulEmptySearches() {
        assertEquals(SearchEvidenceOutcome.AUTH_REQUIRED, evidenceOutcome("authentication_failed", false, false))
        assertEquals(SearchEvidenceOutcome.PARSE_FAILED, evidenceOutcome("unstructured", false, false))
        assertEquals(SearchEvidenceOutcome.EMPTY_VALID, evidenceOutcome("empty", false, false))
        assertEquals(SearchEvidenceOutcome.PARTIAL, evidenceOutcome("budget_exhausted", true, true))
        assertEquals(SearchEvidenceOutcome.CANCELLED, evidenceOutcome("cancelled", false, false))
    }
}
