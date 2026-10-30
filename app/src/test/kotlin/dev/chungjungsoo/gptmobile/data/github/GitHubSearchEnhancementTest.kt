package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchEnhancementTest {
    @Test fun workflowMetadataBoundsRepeatedEntriesAndFlagsBlockMetadata() {
        val bounded = GitHubWorkflowMetadata.parse("name: item\n".repeat(20_000))
        assertTrue(bounded.getValue("warnings").toString().contains("entry limit"))
        val multiline = GitHubWorkflowMetadata.parse("name: |\n  A multiline name\non: push")
        assertTrue(multiline.getValue("warnings").toString().contains("Block scalar"))
    }

    @Test fun fuzzyMatchingIsExplicitAndCaseAware() {
        val exact = GitHubSearchOptions()
        assertEquals(0.0, exact.score("renderResponse", "renderReponse"), 0.001)
        assertTrue(exact.copy(fuzzy = true).score("renderResponse", "renderReponse") > 0.8)
        assertEquals(1.0, exact.score("Target", "target"), 0.001)
        assertEquals(0.0, exact.copy(caseSensitive = true).score("Target", "target"), 0.001)
        assertEquals(0.0, exact.copy(fuzzy = true).score("unrelated", "target"), 0.001)
    }

    @Test fun filtersArePartOfIdentityAndInvalidBudgetsAreRejected() {
        val options = GitHubSearchOptions.from(
            buildJsonObject {
                put("query", "target")
                put("path_suffix", "Test.kt")
                put("context_lines", 4)
            }
        )
        assertNotEquals(options.json(), options.copy(caseSensitive = true).json())
        for (args in listOf(
            buildJsonObject { put("context_lines", 6) },
            buildJsonObject {
                put("min_lines", 20)
                put("max_lines", 10)
            },
            buildJsonObject {
                put("fuzzy_match", true)
                put("query", "two words")
            },
            buildJsonObject { put("fuzzy_threshold", 0.1) }
        )) {
            assertTrue(runCatching { GitHubSearchOptions.from(args) }.isFailure)
        }
    }

    @Test fun fuzzyScanningCooperatesWithCancellationBudget() {
        assertTrue(runCatching { GitHubSearchOptions(fuzzy = true).score("anotherIdentifier", "target") { error("cancelled") } }.isFailure)
    }

    @Test fun contextCarriesExactExcerptAndLabelsHeuristicScope() {
        val lines = listOf("/** Adds one. */", "fun add(value: Int): Int = value + 1", "val result = add(2)")
        val context = GitHubSearchContext.extract(lines, 3, 1)
        assertEquals("2", context.getValue("start_line").jsonPrimitive.content)
        assertEquals("add", context.getValue("nearby_declaration").jsonPrimitive.content)
        assertTrue(context.getValue("documentation").jsonPrimitive.content.contains("Adds one"))
        assertTrue(context.getValue("resolution").jsonPrimitive.content.contains("lexical"))
        assertEquals(1, GitHubSearchContext.related("src/Add.kt", listOf("src/Add.kt", "test/AddTest.kt", "test/AddTest.kt")).size)
    }

    @Test fun workflowOutlineIgnoresExecutableBlockContent() {
        val source = """
            name: Build
            on:
              workflow_dispatch:
                inputs:
                  variant:
                    type: choice
                    default: debug
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - run: |
                      on: malicious_fake_trigger
                      jobs: fake
                  - uses: actions/upload-artifact@sha
                    with:
                      name: app-debug
                      retention-days: 14
        """.trimIndent()
        val result = GitHubWorkflowMetadata.parse(source)
        assertEquals(listOf("workflow_dispatch"), result.getValue("triggers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("variant", result.getValue("inputs").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("14", result.getValue("artifacts").jsonArray.single().jsonObject.getValue("retention-days").jsonPrimitive.content)
        assertEquals("false", result.getValue("expressions_evaluated").jsonPrimitive.content)
    }
}
