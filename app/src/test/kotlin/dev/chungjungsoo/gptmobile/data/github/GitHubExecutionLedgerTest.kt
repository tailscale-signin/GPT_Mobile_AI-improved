package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExecutionLedgerTest {
    private fun receipt(success: Boolean? = null, dispatched: Boolean? = null, phase: String? = null, unknown: Int = 0) = buildJsonObject {
        success?.let { put("success", it) }
        dispatched?.let { put("dispatched", it) }
        put(
            "execution",
            buildJsonObject {
                phase?.let { put("phase", it) }
                put("unknown_mutations", unknown)
            }
        )
    }

    @Test fun dispatchEvidenceDoesNotInferRemoteCompletion() {
        assertEquals(GitHubExecutionLedger.ReceiptState.PRECHECK_REJECTED, GitHubExecutionLedger.receiptState(receipt(false, false)))
        assertEquals(GitHubExecutionLedger.ReceiptState.DISPATCHED, GitHubExecutionLedger.receiptState(receipt(dispatched = true, phase = "DISPATCHED")))
        assertEquals(GitHubExecutionLedger.ReceiptState.COMPLETED, GitHubExecutionLedger.receiptState(receipt(true, true)))
        assertEquals(GitHubExecutionLedger.ReceiptState.FAILED, GitHubExecutionLedger.receiptState(receipt(false, true)))
        assertEquals(GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.receiptState(receipt(true, true, unknown = 1)))
        assertEquals(GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.receiptState(receipt(false)))
    }

    @Test fun exactDurableApprovalRejectionIsNotACompletedMutationOrGenericCancellation() {
        val head = "a".repeat(40)
        val patchDigest = "b".repeat(64)
        val proposal = buildJsonObject {
            put("tool", "github_write")
            put("receipt_id", "proposal")
            put("binding", "bound-task")
            put("repository", "owner/repo")
            put("success", true)
            put("trusted", true)
            put(
                "arguments",
                buildJsonObject {
                    put("branch", "codex-self-test/fixture")
                    put("expected_head_sha", head)
                }
            )
            put(
                "result",
                buildJsonObject {
                    put("status", "AWAITING_APPROVAL")
                    put("committed", false)
                    put("engineering_task_id", "engineering-task")
                    put("branch", "codex-self-test/fixture")
                    put("expected_head_sha", head)
                    put("patch_digest", patchDigest)
                }
            )
        }
        val rejected = buildJsonObject {
            put("tool", "github_context")
            put("receipt_id", "rejection")
            put("binding", "bound-task")
            put("repository", "owner/repo")
            put("success", true)
            put("trusted", true)
            put("evidence_origin", "LOCAL_DURABLE_APPROVAL_EVENT")
            put("local_resolution_event", "approval_card_rejected")
            put(
                "arguments",
                buildJsonObject {
                    put("action", "recovery")
                    put("engineering_task_id", "engineering-task")
                    put("branch", "codex-self-test/fixture")
                }
            )
            put(
                "result",
                buildJsonObject {
                    put("engineering_task_id", "engineering-task")
                    put("branch", "codex-self-test/fixture")
                    put("expected_head_sha", head)
                    put("patch_digest", patchDigest)
                    put("approval_state", "CANCELLED_BEFORE_PUBLICATION")
                    put("cancellation_confirmed", true)
                    put("remote_mutation_performed", false)
                    put("remote_state_verified", false)
                    put("keep_fixture", false)
                }
            )
        }
        assertEquals(GitHubExecutionLedger.ReceiptState.REJECTED, GitHubExecutionLedger.receiptState(rejected))
        assertEquals(GitHubExecutionLedger.ObservationKind.REJECTED, GitHubExecutionLedger.observationKind(rejected))
        val checkpoint = GitHubExecutionLedger.attach(
            GitHubAcceptanceLedger.attach(buildJsonObject { put("task_outcome", "PARTIAL") }),
            mapOf("proposal" to proposal, "rejection" to rejected)
        )
        val ledger = checkpoint.getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive("REJECTED"), checkpoint["task_state"])
        assertEquals(JsonPrimitive("REJECTED"), checkpoint["task_outcome"])
        assertEquals(JsonPrimitive("APPROVAL_REJECTED"), checkpoint["stop_code"])
        assertEquals(JsonPrimitive("REJECTED"), checkpoint["approval_state"])
        assertEquals(JsonPrimitive("REJECTED"), ledger["requested_task"])
        assertEquals(JsonPrimitive("REJECTED"), ledger["verification"])
        assertEquals(JsonPrimitive(0), ledger.getValue("receipt_states").jsonObject["COMPLETED"])
        assertEquals(JsonPrimitive(false), ledger["retryable_without_new_input"])
        assertEquals(GitHubTaskLifecycle.State.REJECTED, GitHubTaskLifecycle.terminal(checkpoint))
    }

    @Test fun deliveredPartialAnswerKeepsTaskAndReviewUnfinishedWithoutInventingAUserDecision() {
        val value = GitHubAcceptanceLedger.attach(
            buildJsonObject {
                put("task_outcome", "PARTIAL")
                put("response_delivered", true)
                put("failure_code", "GITHUB_EXTRACTION_TIMEOUT")
                put(
                    "requirements",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "ci")
                                put("status", "UNTESTED")
                            }
                        )
                    )
                )
            }
        )
        val ledger = GitHubExecutionLedger.attach(value, mapOf("write" to receipt(true, true))).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive("INCOMPLETE"), ledger["requested_task"])
        assertEquals(JsonPrimitive("FAILED"), ledger["verification"])
        assertEquals(JsonPrimitive("DELIVERED"), ledger["delivery"])
        assertEquals(JsonPrimitive("RESUME_VERIFICATION"), ledger["recovery"])
        assertEquals(JsonPrimitive(false), ledger["user_input_required"])
        assertEquals(JsonPrimitive(1), ledger["remaining_criteria"])
    }

    @Test fun unknownWriteMustBeReconciledEvenWhenTheResponseClaimsSuccess() {
        val value = GitHubAcceptanceLedger.attach(
            buildJsonObject {
                put("task_outcome", "COMPLETED")
                put("completion_verified", true)
                put("response_delivered", true)
                put(
                    "requirements",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "write")
                                put("status", "PASSED")
                            }
                        )
                    )
                )
            }
        )
        val checkpoint = GitHubExecutionLedger.attach(value, mapOf("write" to receipt(false, true, unknown = 1)))
        val ledger = checkpoint.getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), ledger["recovery"])
        assertEquals(JsonPrimitive(1), ledger.getValue("receipt_states").jsonObject["UNKNOWN_OUTCOME"])
        assertEquals(JsonPrimitive("INCOMPLETE"), ledger["requested_task"])
        assertEquals(JsonPrimitive("PENDING"), ledger["verification"])
        assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
        assertEquals(JsonPrimitive("PARTIAL"), checkpoint["task_outcome"])
        assertEquals(GitHubTaskLifecycle.State.PARTIAL, GitHubTaskLifecycle.terminal(checkpoint))
    }

    @Test fun allSharedUnknownOutcomeCodesRemainUncertainAfterDispatch() {
        ToolResultEnvelope.unknownOutcomeCodes.forEach { code ->
            val value = JsonObject(receipt(false, true, "DISPATCHED") + ("error_code" to JsonPrimitive(code)))
            assertEquals(code, GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.receiptState(value))
        }
    }

    @Test fun restoredUnknownCommitClearsOnlyAfterMatchingNativeRemoteProof() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            "task",
            "o/r",
            "feature",
            setOf("github_write"),
            save = { id, row -> saved[id] = row },
            load = { saved[it] },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
            latestVerdict = { saved.values.lastOrNull { it["execution_ledger"] != null } }
        )
        val args = commitArguments()
        val original = book()
        original.observe(ProviderEvent.ToolCall("lost-write", "github_write", args), AgentToolResult("lost-write", ToolResultContent.Text("Response lost"), true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN", dispatched = true))
        original.retainVerdict(completedVerdict())
        assertEquals(JsonPrimitive("PARTIAL"), original.diagnostic()?.get("task_state"))
        val restored = book()
        restored.restore()
        val proof = verifiedCommit(args)
        restored.observe(ProviderEvent.ToolCall("read-only-proof", "github_write", args), AgentToolResult("read-only-proof", ToolResultContent.Json(proof), false))
        restored.retainVerdict(completedVerdict())
        val checkpoint = restored.diagnostic()!!
        val ledger = checkpoint.getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive("COMPLETED"), checkpoint["task_state"])
        assertEquals(JsonPrimitive("VERIFIED_COMPLETE"), ledger["requested_task"])
        assertEquals(JsonPrimitive("NONE"), ledger["recovery"])
        assertEquals(JsonPrimitive(0), ledger["unresolved_remote_outcomes"])
        assertEquals(JsonPrimitive(1), ledger["reconciled_remote_outcomes"])
        assertEquals(JsonPrimitive(1), ledger.getValue("receipt_states").jsonObject["UNKNOWN_OUTCOME"])
        assertEquals(2, restored.snapshot().first.size)
    }

    @Test fun modelClaimsAndDifferentTaskArgumentsCannotClearUnknownCommit() = runTest {
        val args = commitArguments()
        val scope = buildJsonObject {
            put("binding", "task")
            put("tool", "github_write")
            put("repository", "o/r")
            put("branch", "feature")
            put("trusted", true)
            put("arguments", args)
        }
        val uncertain = JsonObject(scope + receipt(false, true, unknown = 1))
        val validProof = JsonObject(scope + receipt(true, true) + ("result" to verifiedCommit(args)))
        val invalidProofs = listOf(
            JsonObject(validProof + ("trusted" to JsonPrimitive(false))),
            JsonObject(validProof + ("binding" to JsonPrimitive("another-task"))),
            JsonObject(validProof + ("arguments" to JsonObject(args + ("message" to JsonPrimitive("Different change"))))),
            JsonObject(validProof + ("result" to buildJsonObject { put("reconciled", true) })),
            JsonObject(validProof + ("success" to JsonPrimitive(false)))
        )
        val claimed = JsonObject(completedVerdict() + ("reconciliation_proofs" to buildJsonObject { put("lost", "model-says-complete") }))
        invalidProofs.forEach { proof ->
            val checkpoint = GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(claimed), mapOf("lost" to uncertain, "proof" to proof))
            assertEquals(JsonPrimitive(false), checkpoint["completion_verified"])
            assertEquals(JsonPrimitive(1), checkpoint.getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
        }
    }

    @Test fun exactLiveBranchReadbackSettlesOnlyTheMatchingUncertainCreation() {
        val head = "a".repeat(40)
        val scope = buildJsonObject {
            put("binding", "task")
            put("tool", "github_commit")
            put("repository", "o/r")
            put("branch", "topic")
            put("trusted", true)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "branch")
                    put("branch", "topic")
                    put("expected_head_sha", head)
                }
            )
        }
        val uncertain = JsonObject(scope + receipt(false, true, unknown = 1))
        val proof = JsonObject(
            scope + receipt(true, false, "OBSERVED") + mapOf(
                "evidence_origin" to JsonPrimitive("LIVE"),
                "result" to buildJsonObject {
                    put("branch", "topic")
                    put("head_sha", head)
                    put("base_sha", head)
                    put("reconciled", true)
                    put("remote_state_verified", true)
                    put("verification", "REMOTE_REF_AND_EXPECTED_HEAD")
                    put(
                        "receipt",
                        buildJsonObject {
                            put("ref", "refs/heads/topic")
                            put("object", buildJsonObject { put("sha", head) })
                        }
                    )
                }
            )
        )
        val completed = GitHubAcceptanceLedger.attach(completedVerdict())
        val verified = GitHubExecutionLedger.attach(completed, mapOf("lost" to uncertain, "proof" to proof))
        assertEquals(JsonPrimitive(0), verified.getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
        for (unrelated in listOf(
            JsonObject(proof + ("evidence_origin" to JsonPrimitive("CACHED"))),
            JsonObject(proof + ("binding" to JsonPrimitive("another-task"))),
            JsonObject(
                proof + (
                    "result" to buildJsonObject {
                        put("reconciled", true)
                        put("remote_state_verified", true)
                    }
                    )
            )
        )) {
            val rejected = GitHubExecutionLedger.attach(completed, mapOf("lost" to uncertain, "proof" to unrelated))
            assertEquals(JsonPrimitive(1), rejected.getValue("execution_ledger").jsonObject["unresolved_remote_outcomes"])
        }
    }

    private fun completedVerdict() = buildJsonObject {
        put("task_outcome", "COMPLETED")
        put("completion_verified", true)
        put("claims_verified", true)
        put("response_delivered", true)
        put(
            "requirements",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "write")
                        put("status", "PASSED")
                    }
                )
            )
        )
    }

    private fun commitArguments() = buildJsonObject {
        put("action", "commit")
        put("owner", "o")
        put("repo", "r")
        put("branch", "feature")
        put("expected_head_sha", "a".repeat(40))
        put("message", "Publish")
        put(
            "files",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "new.txt")
                        put("content", "new")
                    }
                )
            )
        )
    }

    private suspend fun verifiedCommit(args: JsonObject): JsonObject = requireNotNull(
        GitHubCommitReconciliation.recover("/repos/o/r", args) { path ->
            when (path) {
                "/repos/o/r/git/ref/heads/feature" -> buildJsonObject {
                    put("ref", "refs/heads/feature")
                    put("object", buildJsonObject { put("sha", "b".repeat(40)) })
                }
                "/repos/o/r/git/commits/${"b".repeat(40)}" -> buildJsonObject {
                    put("sha", "b".repeat(40))
                    put("parents", JsonArray(listOf(buildJsonObject { put("sha", "a".repeat(40)) })))
                    put("message", "Publish")
                    put("tree", buildJsonObject { put("sha", "d".repeat(40)) })
                }
                "/repos/o/r/git/commits/${"a".repeat(40)}" -> buildJsonObject { put("tree", buildJsonObject { put("sha", "c".repeat(40)) }) }
                "/repos/o/r/git/trees/${"c".repeat(40)}?recursive=1" -> buildJsonObject {
                    put("sha", "c".repeat(40))
                    put("truncated", false)
                    put("tree", JsonArray(emptyList()))
                }
                "/repos/o/r/git/trees/${"d".repeat(40)}?recursive=1" -> buildJsonObject {
                    put("sha", "d".repeat(40))
                    put("truncated", false)
                    put(
                        "tree",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("path", "new.txt")
                                    put("mode", "100644")
                                    put("type", "blob")
                                    put("sha", GitHubCommitReconciliation.blobSha("new"))
                                }
                            )
                        )
                    )
                }
                else -> error("Unexpected reconciliation request: $path")
            }
        }
    )

    @Test fun disconnectAndRestartKeepConfirmedWriteReceiptSeparateFromCiAndDelivery() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook(
            "task",
            "o/r",
            "feature",
            setOf("github_context", "github_commit"),
            save = { id, row -> saved[id] = row },
            load = { saved[it] },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
            latestVerdict = { saved.values.lastOrNull { it["execution_ledger"] != null } }
        )
        val original = book()
        val write = ProviderEvent.ToolCall("published-write", "github_commit", buildJsonObject { put("branch", "feature") })
        original.observe(write, AgentToolResult(write.callId, ToolResultContent.Text("Published revision"), false))
        original.retainProgress("Publish changes and verify CI", emptyList(), "CONNECT_TIMEOUT", ProviderEvent.Failed("Connection timed out", errorCode = "CONNECT_TIMEOUT"))
        val restored = book()
        restored.restore()
        assertEquals(1, restored.snapshot().first.size)
        val check = ProviderEvent.ToolCall("fresh-ci", "github_context", buildJsonObject { put("action", "checks") })
        restored.observe(check, AgentToolResult(check.callId, ToolResultContent.Text("Checks succeeded for saved revision"), false))
        val verified = buildJsonObject {
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("claims_verified", true)
            put(
                "requirements",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "ci")
                            put("status", "PASSED")
                        }
                    )
                )
            )
        }
        restored.retainVerdict(verified)
        assertEquals(JsonPrimitive("PARTIAL"), restored.diagnostic()?.get("task_state"))
        assertEquals(JsonPrimitive("DELIVER_VERIFIED_RESPONSE"), restored.diagnostic()!!.getValue("execution_ledger").jsonObject["recovery"])
        restored.retainVerdict(JsonObject(verified + ("response_delivered" to JsonPrimitive(true))))
        assertEquals(JsonPrimitive("COMPLETED"), restored.diagnostic()?.get("task_state"))
        assertTrue(restored.snapshot().first.values.any { it["call_id"] == JsonPrimitive("published-write") })
        assertFalse(restored.diagnostic()!!.getValue("execution_ledger").jsonObject.getValue("user_input_required") == JsonPrimitive(true))
    }
}
