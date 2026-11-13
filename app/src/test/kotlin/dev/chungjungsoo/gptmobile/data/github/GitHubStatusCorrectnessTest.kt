package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusCorrectnessTest {
    private val head = "a".repeat(40)
    private val other = "b".repeat(40)
    private val now = 1_000_000L
    private val health = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
    private val tools: List<AgentToolDefinition> = listOf("github_context", "github_pr", "github_ci").map { GitHubOperationTool.definition(it) }

    @Test fun factLimitCannotHideScopeAfterALargeCheckCollection() {
        val source = checks(1, 100, 132)
        val facts = GitHubEvidencePolicy.facts(source)
        assertTrue(facts.size <= 256)
        assertEquals(JsonPrimitive(head), facts["/commit_sha"])
        assertEquals(JsonPrimitive(132), facts["/checks"])
        assertEquals(JsonPrimitive(true), facts["/has_more"])
        assertEquals(JsonPrimitive(2), facts["/continuation/page"])
    }

    @Test fun statusPreservesAcceptedRequirementIdsAndImmutableSourceProvenance() {
        val receipts = base() + ("checks" to receipt("github_context", args("checks"), checks(1, 3, 3)))
        val original = receipts.toString()
        val accepted = GitHubRequestCompletion.requirements("status")
        val report = requireNotNull(GitHubEvidencePolicy.status(receipts, now, 0, "status", accepted)).second
        val requirements = report.getValue("requirements").jsonArray.map { it.jsonObject }
        assertEquals(accepted, requirements.map { it.getValue("requirement").jsonPrimitive.content })
        assertEquals(accepted.map(GitHubRequestCompletion::id), requirements.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(4, requirements.size)
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        for (requirement in requirements) {
            assertTrue(requirement.getValue("receipt_ids").jsonArray.isNotEmpty())
            for (source in requirement.getValue("source_receipts").jsonArray.map { it.jsonObject }) {
                val id = source.getValue("receipt_id").jsonPrimitive.content
                assertEquals(receipts.getValue(id)["result_sha256"], source["source_result_sha256"])
                assertEquals(JsonPrimitive(now), source["observed_at_ms"])
            }
        }
        assertEquals(original, receipts.toString())
        val retained = accepted + "Inspect an additional separately accepted requirement"
        val unresolved = requireNotNull(GitHubEvidencePolicy.status(receipts, now, 0, "status", retained)).second
        assertEquals(retained.last(), unresolved.getValue("requirements").jsonArray.last().jsonObject.getValue("requirement").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), unresolved["completion_verified"])
    }

    @Test fun aHundredOfOneHundredThirtyTwoChecksStayPartialUntilThePinnedSecondPage() {
        val first = base() + ("checks1" to receipt("github_context", args("checks"), checks(1, 100, 132)))
        val initial = requireNotNull(GitHubEvidencePolicy.status(first, now, 0))
        assertEquals(JsonPrimitive(false), initial.second["completion_verified"])
        assertEquals(JsonPrimitive(100), initial.second.getValue("check_inventory").jsonObject["observed_checks"])
        assertTrue(initial.first.contains("100 of 132 check runs"))
        val next = requireNotNull(GitHubStatusRequest.nextCall(tools, first, now, 0))
        assertEquals(JsonPrimitive("checks"), next.arguments["action"])
        assertEquals(JsonPrimitive(head), next.arguments["commit_sha"])
        assertEquals(JsonPrimitive(2), next.arguments["page"])
        assertEquals(JsonPrimitive("compact"), next.arguments["response_detail"])
        val both = first + ("checks2" to receipt("github_context", next.arguments, checks(2, 32, 132)))
        val completed = requireNotNull(GitHubEvidencePolicy.status(both, now, 0))
        assertEquals(JsonPrimitive(true), completed.second["completion_verified"])
        assertEquals(JsonPrimitive(132), completed.second.getValue("check_inventory").jsonObject["observed_checks"])
        assertTrue(completed.first.contains("132 success"))
        assertEquals(null, GitHubStatusRequest.nextCall(tools, both, now, 0))
    }

    @Test fun repeatedWrongRevisionStaleAndMissingPagesCannotCompleteCheckCoverage() {
        val first = base() + ("checks1" to receipt("github_context", args("checks"), checks(1, 100, 132)))
        val secondArgs = JsonObject(args("checks") + ("page" to JsonPrimitive(2)))
        val good = receipt("github_context", secondArgs, checks(2, 32, 132))
        val variants = listOf(
            JsonObject(good + ("result" to checks(2, 32, 132, sha = other))),
            JsonObject(good + ("observed_at_ms" to JsonPrimitive(now - GitHubEvidencePolicy.MAX_AGE_MS - 1))),
            JsonObject(good + ("trusted" to JsonPrimitive(false))),
            JsonObject(good + ("verification_eligible" to JsonPrimitive(false))),
            receipt("github_context", secondArgs, checks(2, 32, 132, firstId = 1)),
            receipt("github_context", secondArgs, checks(2, 32, 133))
        )
        for (invalid in variants) {
            assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(first + ("checks2" to invalid), now, 0)).second["completion_verified"])
        }
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(base() + ("checks2" to good), now, 0)).second["completion_verified"])
        val refreshed = first + ("checks2" to good) + ("refresh" to receipt("github_context", args("checks"), checks(1, 100, 132)))
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(refreshed, now, 0)).second["completion_verified"])
        assertEquals(JsonPrimitive(2), requireNotNull(GitHubStatusRequest.nextCall(tools, refreshed, now, 0)).arguments["page"])
    }

    @Test fun healthPresetKeepsAllRequestedObligationsAndBoundsItsReadOnlyPlan() {
        val records = (base() + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))).toMutableMap()
        assertTrue(GitHubStatusRequest.health(health))
        assertEquals(7, GitHubRequestCompletion.requirements(health).size)
        val incomplete = requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second
        assertEquals(JsonPrimitive(false), incomplete["completion_verified"])
        val savedCriteria = GitHubRequestCompletion.requirements("status")
        val resumed = requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health, savedCriteria)).second
        assertEquals(savedCriteria, resumed.getValue("requirements").jsonArray.map { it.jsonObject.getValue("requirement").jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), resumed["completion_verified"])
        val branch = requireNotNull(GitHubStatusRequest.nextCall(tools, records, now, 0, health))
        assertEquals(JsonPrimitive("branch"), branch.arguments["action"])
        assertEquals(JsonPrimitive("main"), branch.arguments["branch"])
        records["policy"] = receipt("github_context", branch.arguments, policy())
        val failures = requireNotNull(GitHubStatusRequest.nextCall(tools, records, now, 0, health))
        assertEquals("github_ci", failures.name)
        assertEquals(JsonPrimitive("failure"), failures.arguments["status"])
        assertEquals(JsonPrimitive("main"), failures.arguments["branch"])
        assertFalse("commit_sha" in failures.arguments)
        records["failures"] = receipt(
            "github_ci",
            failures.arguments,
            buildJsonObject {
                put("workflow_runs", JsonArray(emptyList()))
                put("returned", 0)
                put("has_more", false)
            }
        )
        val complete = requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second
        assertEquals(JsonPrimitive(true), complete["completion_verified"])
        val unknown = JsonObject(
            policy() + (
                "policy" to buildJsonObject {
                    put("required_checks_state", "UNKNOWN")
                    put("required_checks_complete", false)
                }
                )
        )
        records["policy"] = receipt("github_context", branch.arguments, unknown)
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second["completion_verified"])
        assertEquals(6, GitHubExecutionLimits.toolCalls("status", 100, GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)))
        assertEquals(6, GitHubExecutionLimits.toolCalls("$health Budget: 8 tool calls.", 100, GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)))
        assertEquals(4, GitHubExecutionLimits.toolCalls("$health Budget: 4 tool calls.", 100, GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)))
    }

    @Test fun reviewDecisionsMustMatchEveryOpenPullRequestHeadAndBase() {
        val records = (base(listOf(pull(881))) + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))).toMutableMap()
        records["policy"] = receipt(
            "github_context",
            buildJsonObject {
                put("action", "branch")
                put("branch", "main")
            },
            policy()
        )
        records["failures"] = receipt(
            "github_ci",
            buildJsonObject {
                put("action", "runs")
                put("branch", "main")
                put("status", "failure")
            },
            buildJsonObject { put("workflow_runs", JsonArray(emptyList())) }
        )
        val next = requireNotNull(GitHubStatusRequest.nextCall(tools, records, now, 0, health))
        assertEquals(JsonPrimitive("context"), next.arguments["action"])
        assertEquals(JsonPrimitive(881), next.arguments["pull_number"])
        assertEquals(JsonPrimitive(head), next.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(other), next.arguments["expected_base_sha"])
        assertEquals(JsonPrimitive(false), next.arguments["include_files"])
        val observed = buildJsonObject {
            put(
                "merge_health",
                buildJsonObject {
                    put("headSha", head)
                    put("baseSha", other)
                    put("review", "REVIEW_REQUIRED")
                }
            )
        }
        records["review"] = receipt("github_pr", next.arguments, observed)
        assertEquals(JsonPrimitive(true), requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second["completion_verified"])
        records["review"] = receipt(
            "github_pr",
            next.arguments,
            buildJsonObject {
                put(
                    "merge_health",
                    buildJsonObject {
                        put("headSha", other)
                        put("baseSha", other)
                        put("review", "APPROVED")
                    }
                )
            }
        )
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second["completion_verified"])
    }

    @Test fun compactStatusCountsKeepOriginalHashAndDoNotEnumerateAFullPage() {
        val original = checks(1, 100, 132)
        val before = original.toString()
        val compact = GitHubResultProjection.project("github_context", JsonObject(args("checks") + ("response_detail" to JsonPrimitive("compact"))), original)
        assertTrue(compact.toString().encodeToByteArray().size < 8 * 1024)
        assertFalse("check_details" in compact)
        assertEquals(JsonPrimitive(100), compact["observed_checks"])
        assertEquals(JsonPrimitive(132), compact["checks"])
        assertEquals(JsonPrimitive(false), compact["check_inventory_complete"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(before)), compact.getValue("projection").jsonObject["source_result_sha256"])
        assertEquals(original["continuation"], compact["continuation"])
        assertEquals(before, original.toString())
        assertEquals(original, GitHubResultProjection.project("github_context", JsonObject(args("checks") + ("response_detail" to JsonPrimitive("full"))), original))
    }

    @Test fun receiptProjectionRetainsAllHundredCheckIdentitiesWithoutRewritingItsHash() {
        val source = checks(1, 100, 132)
        val details = source.getValue("check_details").jsonObject
        val rows = details.getValue("check_runs").jsonArray.map { raw ->
            JsonObject(
                raw.jsonObject + (
                    "app" to buildJsonObject {
                        put("id", 1)
                        put("description", "Repeated application metadata. ".repeat(400))
                    }
                    )
            )
        }
        val large = JsonObject(source + ("check_details" to JsonObject(details + ("check_runs" to JsonArray(rows)))))
        val original = receipt("github_context", args("checks"), large)
        val projected = GitHubEvidenceProjection.receipt(original)
        assertTrue(large.toString().encodeToByteArray().size > 64 * 1024)
        assertEquals(original["result_sha256"], projected["result_sha256"])
        assertEquals(original["result_sha256"], projected["original_result_sha256"])
        val inventory = GitHubStatusEvidence.checks(mapOf("checks" to projected), head, now, 0)
        assertEquals(100, inventory.observedChecks)
        assertEquals(132, inventory.totalChecks)
        assertEquals(2, inventory.nextPage)
        assertFalse(inventory.complete)
    }

    @Test fun aRefreshedPartialPullListDoesNotInheritAnEarlierEmptyInventory() {
        val records = (base() + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))).toMutableMap()
        val args = buildJsonObject {
            put("action", "list")
            put("state", "open")
            put("per_page", 100)
            put("page", 1)
        }
        records["new-pulls"] = receipt(
            "github_pr",
            args,
            buildJsonObject {
                put("items", JsonArray(emptyList()))
                put("page", 1)
                put("has_more", true)
            }
        )
        val coverage = GitHubStatusEvidence.coverage(records, now, 0, health, GitHubStatusEvidence.checks(records, head, now, 0))
        assertFalse(coverage.single { it.item == "Outstanding review decisions" }.available)
        assertEquals(setOf("new-pulls"), GitHubStatusEvidence.openPullPages(records, now, 0).keys)
    }

    @Test fun prBranchesAreLabeledByRoleAndOldCommitHistoryIsExcluded() {
        val records = base(listOf(pull(881))) + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))
        val text = requireNotNull(GitHubEvidencePolicy.status(records, now, 0)).first
        assertTrue(text.contains("PR 881 · Head branch"))
        assertTrue(text.contains("PR 881 · Base branch"))
        assertTrue(text.contains("feature/status"))
        val old = receipt(
            "github_context",
            args("commits"),
            buildJsonObject {
                put("commit_sha", other)
                put(
                    "items",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("sha", other)
                                put("commit", buildJsonObject { put("message", "Old hidden commit") })
                            }
                        )
                    )
                )
            }
        )
        val staleHistory = requireNotNull(GitHubEvidencePolicy.status(records + ("commits" to old), now, 0))
        assertFalse(staleHistory.first.contains("Old hidden commit"))
        assertEquals(JsonPrimitive(false), staleHistory.second["completion_verified"])
    }

    @Test fun aVerifiedAbsenceOfWorkflowRunsDiffersFromAnUnavailableRead() {
        val records = base() + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))
        val context = records.getValue("status").getValue("result").jsonObject
        for ((availability, complete) in listOf("No workflow run was returned for the selected ref" to true, "Workflow read was unavailable" to false)) {
            val source = JsonObject(context - "latest_workflow" + ("status_availability" to buildJsonObject { put("latest_workflow", availability) }))
            val updated = records + ("status" to receipt("github_context", args("status"), source))
            assertEquals(JsonPrimitive(complete), requireNotNull(GitHubEvidencePolicy.status(updated, now, 0)).second["completion_verified"])
        }
    }

    @Test fun noHealthCapabilityAndNoKnownPolicyRemainPartial() {
        val records = base() + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))
        val onlyReads = tools.filter { it.name == "github_pr" }
        assertEquals(null, GitHubStatusRequest.nextCall(onlyReads, records, now, 0, health))
        val report = requireNotNull(GitHubEvidencePolicy.status(records, now, 0, health)).second
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertNotNull(report["remaining_requirements"])
    }

    @Test fun readSourceStatusAndSameShaForkChecksCannotSatisfyTheAcceptedRepository() {
        val target = base().filterKeys { it != "prs" }.mapValues { scoped(it.value) }
        val source = base().mapValues { scoped(it.value, "upstream/fork", "READ_SOURCE") }
        val records = target + source.mapKeys { "source-${it.key}" } +
            ("source-checks" to scoped(receipt("github_context", args("checks"), checks(1, 0, 0)), "upstream/fork", "READ_SOURCE"))
        val unchanged = records.toString()
        val next = requireNotNull(GitHubStatusRequest.nextCall(tools, records, now, 0, acceptedRepository = "owner/repo"))
        assertEquals(JsonPrimitive("checks"), next.arguments["action"])
        assertEquals(JsonPrimitive(head), next.arguments["commit_sha"])
        val initial = requireNotNull(GitHubEvidencePolicy.status(records, now, 0, acceptedRepository = "owner/repo")).second
        assertEquals(JsonPrimitive(false), initial["completion_verified"])
        assertEquals(JsonPrimitive(0), initial.getValue("check_inventory").jsonObject["observed_checks"])
        val withChecks = records + ("target-checks" to scoped(receipt("github_context", args("checks"), checks(1, 0, 0))))
        assertEquals(JsonPrimitive("list"), requireNotNull(GitHubStatusRequest.nextCall(tools, withChecks, now, 0, acceptedRepository = "owner/repo")).arguments["action"])
        assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(withChecks, now, 0, acceptedRepository = "owner/repo")).second["completion_verified"])
        assertEquals(unchanged, records.toString())
    }

    @Test fun repositoryIdentityAndDestinationRoleAreBothRequiredForAcceptedStatus() {
        val records = (base() + ("checks" to receipt("github_context", args("checks"), checks(1, 0, 0)))).mapValues { scoped(it.value) }
        assertEquals(JsonPrimitive(true), requireNotNull(GitHubEvidencePolicy.status(records, now, 0, acceptedRepository = "OWNER/REPO")).second["completion_verified"])
        for ((repo, role) in listOf("upstream/fork" to "WRITE_DESTINATION", "owner/repo" to "READ_SOURCE", "owner/repo" to "LIFECYCLE_DESTINATION", "owner/repo" to "")) {
            val wrong = records + ("checks" to scoped(records.getValue("checks"), repo, role))
            assertEquals(JsonPrimitive(false), requireNotNull(GitHubEvidencePolicy.status(wrong, now, 0, acceptedRepository = "owner/repo")).second["completion_verified"])
        }
    }

    @Test fun mixedPullPageSizesCannotProveTheOpenReviewInventoryComplete() {
        val records = base().toMutableMap()
        fun page(number: Int, perPage: Int, rows: List<JsonObject>, more: Boolean) = receipt(
            "github_pr",
            buildJsonObject {
                put("action", "list")
                put("state", "open")
                put("page", number)
                put("per_page", perPage)
            },
            buildJsonObject {
                put("items", JsonArray(rows))
                put("page", number)
                put("per_page", perPage)
                put("has_more", more)
            }
        )
        records["prs"] = page(1, 5, (1..5).map(::pull), true)
        records["prs2"] = page(2, 100, listOf(pull(106)), false)
        for (number in (1..5) + 106) {
            records["review-$number"] = receipt(
                "github_pr",
                buildJsonObject {
                    put("action", "context")
                    put("pull_number", number)
                    put("include_health", true)
                },
                buildJsonObject {
                    put(
                        "merge_health",
                        buildJsonObject {
                            put("headSha", head)
                            put("baseSha", other)
                            put("review", "APPROVED")
                        }
                    )
                }
            )
        }
        fun reviewed() = GitHubStatusEvidence.coverage(records, now, 0, health, GitHubStatusEvidence.CheckInventory()).single { it.item == "Outstanding review decisions" }.available
        assertFalse(reviewed())
        records["prs2"] = page(2, 5, listOf(pull(106)), false)
        assertTrue(reviewed())
        records["prs2"] = JsonObject(records.getValue("prs2") + ("result" to JsonObject(records.getValue("prs2").getValue("result").jsonObject + ("per_page" to JsonPrimitive(100)))))
        assertFalse(reviewed())
    }

    @Test fun mixedCheckPageSizesCannotCompleteEvenWhenCountsCoincidentallyMatch() {
        fun page(number: Int, perPage: Int, returned: Int) = receipt("github_context", JsonObject(args("checks") + mapOf("page" to JsonPrimitive(number), "per_page" to JsonPrimitive(perPage))), JsonObject(checks(number, returned, 6) + mapOf("per_page" to JsonPrimitive(perPage), "has_more" to JsonPrimitive(number == 1))))
        val first = page(1, 5, 5)
        val mixed = mapOf("first" to first, "second" to page(2, 100, 1))
        assertFalse(GitHubStatusEvidence.checks(mixed, head, now, 0).complete)
        assertTrue(GitHubStatusEvidence.checks(mapOf("first" to first, "second" to page(2, 5, 1)), head, now, 0).complete)
    }

    @Test fun refreshedPullListDoesNotRenderOlderDraftAndHeadObservationsAsCurrent() {
        val old = JsonObject(
            pull(881) + mapOf(
                "title" to JsonPrimitive("Old draft title"),
                "head" to buildJsonObject {
                    put("ref", "old-pr-branch")
                    put("sha", head)
                }
            )
        )
        val updated = JsonObject(
            pull(881) + mapOf(
                "title" to JsonPrimitive("Current ready title"),
                "draft" to JsonPrimitive(false),
                "head" to buildJsonObject {
                    put("ref", "new-pr-branch")
                    put("sha", other)
                }
            )
        )
        val records = base(listOf(old)) + (
            "updated-prs" to receipt(
                "github_pr",
                args("list"),
                buildJsonObject {
                    put("items", JsonArray(listOf(updated)))
                    put("has_more", false)
                    put("page", 1)
                }
            )
            )
        val original = records.toString()
        val text = requireNotNull(GitHubEvidencePolicy.status(records, now, 0)).first
        assertTrue(text.contains("Current ready title"))
        assertTrue(text.contains("new-pr-branch"))
        assertFalse(text.contains("Old draft title"))
        assertFalse(text.contains("old-pr-branch"))
        assertEquals(original, records.toString())
    }

    private fun scoped(receipt: JsonObject, repository: String = "owner/repo", role: String = "WRITE_DESTINATION") = JsonObject(receipt + mapOf("repository" to JsonPrimitive(repository), "repository_role" to JsonPrimitive(role)))

    private fun args(action: String) = buildJsonObject {
        put("action", action)
        if (action in setOf("checks", "commits")) put("commit_sha", head)
    }

    private fun receipt(tool: String, arguments: JsonObject, source: JsonObject) = buildJsonObject {
        put("tool", tool)
        put("arguments", arguments)
        put("success", true)
        put("trusted", true)
        put("verification_eligible", true)
        put("observed_at_ms", now)
        put("epoch", 0)
        put("result", source)
        put("result_sha256", GitHubEvidenceBook.digest(source.toString()))
    }

    private fun base(pulls: List<JsonObject> = emptyList()) = linkedMapOf(
        "status" to receipt(
            "github_context",
            args("status"),
            buildJsonObject {
                put("repository", buildJsonObject { put("full_name", "owner/repo") })
                put("branch", "main")
                put("commit_sha", head)
                put(
                    "latest_workflow",
                    buildJsonObject {
                        put("head_sha", head)
                        put("head_branch", "main")
                        put("status", "completed")
                    }
                )
            }
        ),
        "prs" to receipt(
            "github_pr",
            args("list"),
            buildJsonObject {
                put("items", JsonArray(pulls))
                put("page", 1)
                put("has_more", false)
            }
        ),
        "commits" to receipt(
            "github_context",
            args("commits"),
            buildJsonObject {
                put("commit_sha", head)
                put("items", JsonArray(emptyList()))
            }
        )
    )

    private fun pull(number: Int) = buildJsonObject {
        put("number", number)
        put("title", "Status correctness")
        put("draft", true)
        put("state", "open")
        put(
            "head",
            buildJsonObject {
                put("ref", "feature/status")
                put("sha", head)
            }
        )
        put(
            "base",
            buildJsonObject {
                put("ref", "main")
                put("sha", other)
            }
        )
    }

    private fun policy() = buildJsonObject {
        put(
            "branch",
            buildJsonObject {
                put("name", "main")
                put("commit", buildJsonObject { put("sha", head) })
            }
        )
        put(
            "policy",
            buildJsonObject {
                put("required_checks_state", "VERIFIED_NONE")
                put("required_checks_complete", true)
            }
        )
    }

    private fun checks(page: Int, returned: Int, total: Int, sha: String = head, firstId: Int = (page - 1) * 100 + 1) = buildJsonObject {
        // Deliberately put the large collection before its scope and count fields.
        put(
            "check_details",
            buildJsonObject {
                put("total_count", total)
                put(
                    "check_runs",
                    JsonArray(
                        List(returned) { index ->
                            buildJsonObject {
                                put("id", firstId + index)
                                put("name", "Test ${firstId + index}")
                                put("head_sha", sha)
                                put("status", "completed")
                                put("conclusion", "success")
                            }
                        }
                    )
                )
            }
        )
        put("commit_sha", sha)
        put("checks", total)
        put("statuses", 0)
        put(
            "status_details",
            buildJsonObject {
                put("total_count", 0)
                put("statuses", JsonArray(emptyList()))
            }
        )
        put("state", if (returned == total) "PASSED" else "UNKNOWN")
        put("page", page)
        put("has_more", page * 100 < total)
        if (page * 100 < total) {
            put(
                "continuation",
                buildJsonObject {
                    put("action", "get_commit_status_summary")
                    put("commit_sha", sha)
                    put("page", page + 1)
                }
            )
        }
    }
}
