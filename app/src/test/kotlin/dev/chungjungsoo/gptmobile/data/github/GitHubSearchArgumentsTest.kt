package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchArgumentsTest {
    @Test fun emptyPathSearchOffersADiscoveryRouteWithoutInventingAQuery() {
        val args = buildJsonObject {
            put("mode", "paths")
            put("query", "  ")
        }
        val failure = requireNotNull(GitHubSearchArguments.failure(args))
        assertEquals(GitHubSearchArgumentFailure.Rule.QUERY_LENGTH, failure.rule)
        assertTrue(failure.message.orEmpty().contains("filename or star pattern"))
        assertNull(GitHubSearchArguments.pathQualifierCorrection(args))
    }

    @Test fun filenameQualifierFailsBeforeScanningAndOffersOnlyASinglePathPattern() {
        val arguments = buildJsonObject {
            put("query", "filename:*INSTructions* OR filename:*instructions*")
            put("owner", "owner")
            put("repo", "repo")
            put("commit_sha", "a".repeat(40))
            put("path_prefix", ".github/")
        }
        assertEquals(GitHubSearchArgumentFailure.Rule.QUERY_QUALIFIER, GitHubSearchArguments.failure(arguments)?.rule)
        val corrected = requireNotNull(GitHubSearchArguments.pathQualifierCorrection(arguments))
        assertEquals(JsonObject(arguments - "query" + mapOf("mode" to JsonPrimitive("paths"), "query" to JsonPrimitive("*INSTructions*"))), corrected)
        assertEquals(1.0, GitHubSearchArguments.validate(corrected).score(".github/instructions/engineering.md", "*INSTructions*"), 0.0)
        for (changed in listOf(
            arguments + ("case_sensitive" to JsonPrimitive(true)),
            arguments + ("query" to JsonPrimitive("filename:AGENTS.md OR filename:CLAUDE.md")),
            arguments + ("query" to JsonPrimitive("filename:AGENTS.md owner:example")),
            arguments + ("index_cursor" to JsonPrimitive(4)),
            arguments + ("search_id" to JsonPrimitive("issued"))
        )) {
            assertNull(GitHubSearchArguments.pathQualifierCorrection(JsonObject(changed)))
        }
    }

    @Test fun explicitLiteralModePreservesQualifierAndBooleanTextWithoutReinterpretation() {
        for (query in listOf("filename:AGENTS.md OR filename:CLAUDE.md", "path:src extension:kt", "left OR right")) {
            val arguments = buildJsonObject {
                put("query", query)
                put("mode", "literal")
            }
            val options = GitHubSearchArguments.validate(arguments)
            assertEquals(1.0, options.score("literal text $query", query), 0.0)
            assertEquals(0.0, options.score(query.substringBefore(" OR ").substringAfter(':'), query), 0.0)
            assertNull(GitHubSearchArguments.pathQualifierCorrection(arguments))
        }
    }

    @Test fun rejectedModesPatternsRangesAndTypesIdentifyTheirExactRuleWithoutEchoingValues() {
        val cases = listOf(
            Triple(buildJsonObject { put("mode", "private-unsupported-mode") }, "mode", GitHubSearchArgumentFailure.Rule.MODE),
            Triple(
                buildJsonObject {
                    put("mode", "patterns")
                    put("query", "private.*query")
                },
                "query",
                GitHubSearchArgumentFailure.Rule.PATTERN_SYNTAX
            ),
            Triple(
                buildJsonObject {
                    put("min_lines", 20)
                    put("max_lines", 10)
                },
                "max_lines",
                GitHubSearchArgumentFailure.Rule.LINE_ORDER
            ),
            Triple(buildJsonObject { put("context_lines", 101) }, "context_lines", GitHubSearchArgumentFailure.Rule.RANGE),
            Triple(buildJsonObject { put("batch_size", "1") }, "batch_size", GitHubSearchArgumentFailure.Rule.TYPE),
            Triple(buildJsonObject { put("fuzzy_match", JsonNull) }, "fuzzy_match", GitHubSearchArgumentFailure.Rule.TYPE),
            Triple(buildJsonObject { put("fuzzy_threshold", 0.49) }, "fuzzy_threshold", GitHubSearchArgumentFailure.Rule.RANGE),
            Triple(buildJsonObject { put("query", "private path:secret") }, "query", GitHubSearchArgumentFailure.Rule.QUERY_QUALIFIER),
            Triple(
                buildJsonObject {
                    put("mode", "patterns")
                    put("fuzzy_match", true)
                },
                "fuzzy_match",
                GitHubSearchArgumentFailure.Rule.PATTERN_FUZZY
            )
        )
        for ((overrides, field, rule) in cases) {
            val arguments = JsonObject(mapOf("query" to JsonPrimitive("privateIdentifier")) + overrides)
            val failure = requireNotNull(GitHubSearchArguments.failure(arguments))
            assertEquals(field, failure.field)
            assertEquals(rule, failure.rule)
            assertTrue(failure.message.orEmpty().startsWith("$field:"))
            assertTrue(failure.reportFields(arguments).values.none { it.toString().contains("private") || it.toString().contains("secret") })
        }
    }

    @Test fun declaredBoundaryValuesAndSupportedLiteralPatternsRemainValid() {
        val lower = buildJsonObject {
            put("query", "target")
            put("fuzzy_threshold", 0.5)
            put("min_lines", 0)
            put("max_lines", 1)
            put("context_lines", 0)
            put("batch_size", 1)
            put("max_new_blobs", 1)
        }
        val upper = buildJsonObject {
            put("query", "a".repeat(200))
            put("path_suffix", "a".repeat(200))
            put("fuzzy_threshold", 1.0)
            put("min_lines", 1_000_000)
            put("max_lines", 1_000_000)
            put("context_lines", 100)
            put("batch_size", 1000)
            put("max_new_blobs", 100)
            put("file_extension", ".KT")
        }
        for (arguments in listOf(
            lower,
            upper,
            buildJsonObject {
                put("query", "*")
                put("mode", "paths")
            },
            buildJsonObject {
                put("query", "class*Test")
                put("mode", "patterns")
            }
        )) {
            assertNull(GitHubSearchArguments.failure(arguments))
        }
    }
}
