package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInventoryObservationTest {
    @Test fun projectedSearchRetainsItsValidatedPathsAndRevisionForAllDependentReads() {
        val commit = "a".repeat(40)
        val blob = "b".repeat(40)
        val original = Json.parseToJsonElement("""{"commit_sha":"$commit","items":[{"path":"docs/README.md","sha":"$blob","text":"${"private source ".repeat(200)}"}],"has_more":true,"complete":false}""").jsonObject
        val retained = requireNotNull(GitHubInventoryObservation.capture(original, buildJsonObject { put("items", JsonArray(emptyList())) }))
        val row = (retained["planning_records"] as JsonArray).single().jsonObject
        assertEquals(
            buildJsonObject {
                put("path", "docs/README.md")
                put("sha", blob)
            },
            row
        )
        assertEquals(JsonPrimitive(1), retained["fetched"])
        assertEquals(JsonPrimitive(0), retained["displayed"])
        assertEquals(JsonNull, retained["consumed"])
        assertEquals(JsonPrimitive(false), retained["complete"])
        val persisted = buildJsonObject {
            put("binding", "task")
            put("repository", "owner/repo")
            put("trusted", true)
            put("success", true)
            put("tool", "github_search")
            put(
                "arguments",
                buildJsonObject {
                    put("action", "search")
                    put("commit_sha", commit)
                }
            )
            put(
                "result",
                buildJsonObject {
                    put("observationTruncated", true)
                    put("excerpt", JsonObject(emptyMap()))
                }
            )
            put("inventory_observation", retained)
        }
        val restored = Json.parseToJsonElement(persisted.toString()).jsonObject
        assertEquals(JsonPrimitive(commit), GitHubInventoryObservation.planningRoot(restored)?.get("commit_sha"))
        val status = Json.parseToJsonElement("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$commit"}}""").jsonObject
        val tools = listOf("github_read", "github_search").map { GitHubOperationTool.definition(it) }
        val reads = GitHubSelfTestReads.available(tools, listOf(status, restored)).filter { it.arguments["action"] in setOf(JsonPrimitive("ranges"), JsonPrimitive("search_repository"), JsonPrimitive("search_code")) }
        assertEquals(3, reads.size)
        reads.forEach { call ->
            assertEquals(JsonPrimitive(commit), call.arguments["commit_sha"])
            val path = call.arguments["path_suffix"] ?: (call.arguments["ranges"] as JsonArray).single().jsonObject["path"]
            assertEquals(JsonPrimitive("docs/README.md"), path)
            assertTrue(GitHubSelfTestReads.valid(tools.single { it.name == call.name }, call.arguments))
        }
        for (changed in listOf(
            JsonObject(restored + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(restored + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(restored + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(restored + ("inventory_observation" to JsonObject(retained + ("commit_sha" to JsonPrimitive("c".repeat(40))))))
        )) {
            assertEquals(null, GitHubInventoryObservation.searchPath(listOf(status, changed)))
        }
    }

    @Test fun searchPlanningRejectsInvalidPathBlobAndConflictingRevisionWithoutChangingFetchedCounts() {
        val commit = "a".repeat(40)
        val invalidRows = listOf(
            buildJsonObject {
                put("path", "../private")
                put("sha", "b".repeat(40))
            },
            buildJsonObject {
                put("path", "/absolute")
                put("sha", "b".repeat(40))
            },
            buildJsonObject {
                put("path", "docs\\file")
                put("sha", "b".repeat(40))
            },
            buildJsonObject {
                put("path", "a".repeat(4097))
                put("sha", "b".repeat(40))
            },
            buildJsonObject {
                put("path", true)
                put("sha", "b".repeat(40))
            },
            buildJsonObject {
                put("path", "README.md")
                put("sha", "moving-ref")
            }
        )
        val original = buildJsonObject {
            put("commit_sha", commit)
            put("items", JsonArray(invalidRows))
        }
        val retained = requireNotNull(GitHubInventoryObservation.capture(original))
        assertEquals(JsonPrimitive(invalidRows.size), retained["fetched"])
        assertTrue((retained["planning_records"] as JsonArray).all { "path" !in it.jsonObject && "sha" !in it.jsonObject })
        val conflicting = buildJsonObject {
            put(
                "result",
                buildJsonObject {
                    put("commit_sha", "c".repeat(40))
                    put("items", JsonArray(emptyList()))
                }
            )
            put("inventory_observation", retained)
        }
        assertEquals(null, GitHubInventoryObservation.planningRoot(conflicting))
    }

    private fun source(field: String = "workflow_runs") = buildJsonObject {
        put("page", 1)
        put("total_count", 18)
        put("has_more", false)
        put(
            field,
            JsonArray(
                (1..18).map { index ->
                    buildJsonObject {
                        put("id", index)
                        put("head_sha", "a".repeat(40))
                        put("status", "completed")
                        put("conclusion", if (index <= 7) "success" else "skipped")
                        put("details", "retained payload ".repeat(100))
                    }
                }
            )
        )
    }
    private fun receipt(original: JsonObject, action: String = "runs") = buildJsonObject {
        put("receipt_id", "runs")
        put("tool", if (action == "runs") "github_ci" else "github_context")
        put("trusted", true)
        put("success", true)
        put("arguments", buildJsonObject { put("action", action) })
        put("result", GitHubEvidenceProjection.result(original, 400))
        put("review_projection", "EXACT_VALUES_WITH_DURABLE_ORIGINAL")
        put("inventory_observation", requireNotNull(GitHubInventoryObservation.capture(original, buildJsonObject { put("workflow_runs", JsonArray((original["workflow_runs"] as? JsonArray).orEmpty().take(3))) })))
        put("retained_result_complete", true)
        put("result_sha256", "retained-hash")
        put("retained_remote_continuations", JsonArray(emptyList()))
    }

    @Test fun fetchedCoverageSurvivesProjectionAndStaysSeparateFromDisplayAndConsumption() {
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("runs" to receipt(source()))).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
        assertEquals(JsonPrimitive(18), row["fetched"])
        assertEquals(JsonPrimitive(3), row["displayed"])
        assertEquals(JsonNull, row["consumed"])
        assertEquals(JsonPrimitive("NOT_RECORDED"), row["consumption_status"])
        assertEquals(JsonPrimitive("retrieval complete at observation"), row["coverage"])
    }

    @Test fun aSuppressionReceiptCannotReplaceTheCanonicalPageOrResetItsCount() {
        val original = receipt(source())
        val suppression = JsonObject(original - "inventory_observation" + ("result" to buildJsonObject { put("schema", "github.repeat.v1") }))
        val row = GitHubTestChecklist.inventoryCoverage(linkedMapOf("runs" to original, "repeat" to suppression)).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
    }

    @Test fun commitHistoryAliasAndMissingKeysAreCountedWithoutInventingCompleteness() {
        val commits = source("recent_commits")
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("commits" to receipt(commits, "commits"))).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
        val malformed = JsonObject(source() + ("workflow_runs" to JsonArray(listOf(JsonObject(emptyMap())))))
        val broken = GitHubTestChecklist.inventoryCoverage(mapOf("broken" to receipt(malformed))).single().jsonObject
        assertEquals(JsonPrimitive(1), broken["fetched"])
        assertTrue(broken["coverage"] != JsonPrimitive("retrieval complete at observation"))
    }

    @Test fun commitCoverageRetainsTheObservedRevisionAcrossProjectionAndRejectsAFailedBranchRefresh() {
        val sha = "a".repeat(40)
        val branch = buildJsonObject {
            put("action", "commits")
            put("ref", "main")
            put("per_page", 1)
        }
        fun page(number: Int, revision: String) = buildJsonObject {
            put("items", JsonArray(listOf(buildJsonObject { put("sha", "commit-$number") })))
            put("commit_sha", revision)
            put("per_page", 1)
            put("page", number)
            put("has_more", number == 1)
        }
        fun saved(number: Int, revision: String): JsonObject {
            val result = page(number, revision)
            val args = if (number == 1) {
                branch
            } else {
                buildJsonObject {
                    put("action", "commits")
                    put("ref", revision)
                    put("commit_sha", revision)
                    put("per_page", 1)
                    put("page", number)
                }
            }
            return JsonObject(
                receipt(result, "commits") + mapOf(
                    "arguments" to args,
                    "result" to JsonObject(result - "commit_sha")
                )
            )
        }
        val records = linkedMapOf("first" to saved(1, sha), "last" to saved(2, sha))
        val row = GitHubTestChecklist.inventoryCoverage(records).single().jsonObject
        assertEquals(JsonPrimitive("retrieval complete at observation"), row["coverage"])
        assertEquals(JsonPrimitive(sha), row.getValue("filters").jsonObject["commit_sha"])
        assertEquals(JsonPrimitive(2), row["fetched"])

        val moved = records + ("last" to saved(2, "b".repeat(40)))
        assertTrue(GitHubTestChecklist.inventoryCoverage(moved).all { it.jsonObject["coverage"] != JsonPrimitive("retrieval complete at observation") })
        val failed = JsonObject(records.getValue("first") - "result" - "inventory_observation" + ("success" to JsonPrimitive(false)))
        val afterFailure = GitHubTestChecklist.inventoryCoverage(records + ("failed" to failed)).single().jsonObject
        assertEquals(JsonPrimitive("incomplete or pending pagination"), afterFailure["coverage"])
    }

    @Test fun legacyDispatchCountsRemainQualifiedWithoutInventingMissingRowsOrConsumption() {
        val legacy = Json.parseToJsonElement("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs","page":1,"per_page":30},"result":{"observationTruncated":true,"excerpt":{"workflow_runs":[{"id":7}],"total_count":67}},"execution":{"schema":"github.dispatch.v1","parent_result_successful":true,"pagination_progress":{"schema":"github.pagination-progress.v1","remote_page":1,"upstream_rows_returned":30,"reported_total":67}},"retained_remote_continuations":[{"call":{"tool":"github_ci","arguments":{"action":"runs","page":2,"per_page":30}}}]}""").jsonObject
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("legacy" to legacy)).single().jsonObject
        assertEquals(JsonPrimitive(30), row["fetched"])
        assertEquals(JsonPrimitive("67"), row["total"])
        assertEquals(JsonPrimitive(1), row["retrieved"])
        assertEquals(JsonPrimitive("QUALIFIED_DISPATCH_PAGE_METADATA"), row["fetched_count_scope"])
        assertEquals(JsonNull, row["consumed"])
        assertTrue(GitHubTestChecklist.inventorySummary(row).contains("30 of 67 fetched"))
        assertTrue(row["coverage"] != JsonPrimitive("retrieval complete at observation"))
        val otherPage = JsonObject(
            legacy + (
                "arguments" to buildJsonObject {
                    put("action", "runs")
                    put("page", 2)
                }
                )
        )
        assertEquals(null, GitHubInventoryObservation.of(otherPage))
        assertEquals(null, GitHubInventoryObservation.of(JsonObject(legacy + ("success" to JsonPrimitive(false)))))
    }

    @Test fun canonicalRunSelectorsSurviveAProjectionThatRetainsOnlyTheFirstSkippedRun() {
        val original = source()
        val observed = receipt(original)
        val runs = GitHubObservedWorkflowRuns.current(listOf(observed), JsonPrimitive("a".repeat(40)))
        assertEquals(18, runs.size)
        assertEquals(7, runs.count { it["conclusion"] == JsonPrimitive("success") })
        assertEquals(JsonPrimitive(18), GitHubInventoryObservation.of(observed)?.get("fetched"))
    }

    @Test fun completeRunCoverageDoesNotSplitWhenContinuationAddsSelectedRef() {
        val receipts = linkedMapOf<String, JsonObject>()
        for (page in 1..7) {
            val original = buildJsonObject {
                put("page", page)
                put("per_page", 30)
                put("total_count", 206)
                put("has_more", page < 7)
                put("workflow_runs", JsonArray(((page - 1) * 30 + 1..minOf(page * 30, 206)).map { buildJsonObject { put("id", it) } }))
            }
            receipts["page-$page"] = JsonObject(
                receipt(original) + mapOf(
                    "arguments" to buildJsonObject {
                        put("action", "runs")
                        put("page", page)
                        put("per_page", 30)
                        put("commit_sha", "a".repeat(40))
                        if (page > 1) put("ref", "main")
                    },
                    "repository" to JsonPrimitive("owner/repo"),
                    "binding" to JsonPrimitive("same-task")
                )
            )
        }
        val row = GitHubTestChecklist.inventoryCoverage(receipts).single().jsonObject
        assertEquals(JsonPrimitive(206), row["retrieved"])
        assertEquals(JsonPrimitive(206), row["fetched"])
        assertEquals(JsonPrimitive(7), row["page_count"])
        assertEquals(JsonPrimitive("retrieval complete at observation"), row["coverage"])
        assertEquals(JsonPrimitive("a".repeat(40)), row.getValue("filters").jsonObject["head_sha"])
        val foreign = JsonObject(receipts.getValue("page-7") + ("repository" to JsonPrimitive("owner/other")))
        assertEquals(2, GitHubTestChecklist.inventoryCoverage(receipts + ("other" to foreign)).size)
    }

    @Test fun changedUpstreamInventoryRemainsIncompleteEvenWhenCountsMatch() {
        val changed = JsonObject(
            source() + (
                "pagination_progress" to buildJsonObject {
                    put("upstream_changed_during_traversal", true)
                }
                )
        )
        val observed = receipt(changed)
        assertEquals(JsonPrimitive(true), GitHubInventoryObservation.of(observed)?.get("upstream_changed_during_traversal"))
        val row = GitHubTestChecklist.inventoryCoverage(mapOf("runs" to observed)).single().jsonObject
        assertEquals(JsonPrimitive(18), row["retrieved"])
        assertTrue(row["coverage"] != JsonPrimitive("retrieval complete at observation"))
    }

    @Test fun aFreshFirstPageCannotBorrowAnEarlierTraversalsFinalPage() {
        fun page(id: String, page: Int, ids: List<Int>, at: Long, cached: Boolean = false) = buildJsonObject {
            put("receipt_id", id)
            put("tool", "github_ci")
            put("trusted", true)
            put("success", true)
            put("shared_result", cached)
            put("evidence_origin", if (cached) "CACHED" else "LIVE")
            put("observed_at_ms", at)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "runs")
                    put("page", page)
                    put("per_page", 2)
                }
            )
            put(
                "result",
                buildJsonObject {
                    put("page", page)
                    put("total_count", 4)
                    put("has_more", page == 1)
                    put("workflow_runs", JsonArray(ids.map { buildJsonObject { put("id", it) } }))
                }
            )
        }
        val before = linkedMapOf(
            "old-one" to page("old-one", 1, listOf(1, 2), 100),
            "old-two" to page("old-two", 2, listOf(3, 4), 110)
        )
        assertEquals(JsonPrimitive("retrieval complete at observation"), GitHubTestChecklist.inventoryCoverage(before).single().jsonObject["coverage"])
        val refreshed = before + ("new-one" to page("new-one", 1, listOf(5, 6), 200))
        val first = GitHubTestChecklist.inventoryCoverage(refreshed).single().jsonObject
        assertEquals(JsonPrimitive(1), first["page_count"])
        assertEquals(JsonPrimitive(2), first["retrieved"])
        assertTrue(first["coverage"] != JsonPrimitive("retrieval complete at observation"))
        val cached = refreshed + ("cached-old-two" to page("cached-old-two", 2, listOf(3, 4), 110, cached = true))
        val stale = GitHubTestChecklist.inventoryCoverage(cached).single().jsonObject
        assertEquals(JsonPrimitive(1), stale["cached_page_freshness_unverified"])
        assertTrue(stale["coverage"] != JsonPrimitive("retrieval complete at observation"))
        val final = cached + ("new-two" to page("new-two", 2, listOf(7, 8), 210))
        val complete = GitHubTestChecklist.inventoryCoverage(final).single().jsonObject
        assertEquals(JsonPrimitive(4), complete["retrieved"])
        assertEquals(JsonPrimitive(0), complete["cached_page_freshness_unverified"])
        assertEquals(JsonPrimitive("retrieval complete at observation"), complete["coverage"])
    }

    @Test fun anotherRepositoryOrTaskCannotClearAnInventoryReadFailure() {
        val original = JsonObject(receipt(source()) + mapOf("repository" to JsonPrimitive("owner/repo"), "binding" to JsonPrimitive("task-a")))
        val failed = JsonObject(original + mapOf("success" to JsonPrimitive(false), "result" to JsonObject(emptyMap())))
        for (foreign in listOf(
            JsonObject(original + ("repository" to JsonPrimitive("owner/other"))),
            JsonObject(original + ("binding" to JsonPrimitive("task-b")))
        )) {
            val coverage = GitHubTestChecklist.inventoryCoverage(linkedMapOf("old" to original, "failed" to failed, "foreign" to foreign))
            assertEquals(2, coverage.size)
            assertTrue(coverage.first().jsonObject["coverage"] != JsonPrimitive("retrieval complete at observation"))
            assertEquals(JsonPrimitive("retrieval complete at observation"), coverage.last().jsonObject["coverage"])
        }
        val recovered = GitHubTestChecklist.inventoryCoverage(linkedMapOf("old" to original, "failed" to failed, "recovered" to original))
        assertEquals(JsonPrimitive("retrieval complete at observation"), recovered.single().jsonObject["coverage"])
    }
}
