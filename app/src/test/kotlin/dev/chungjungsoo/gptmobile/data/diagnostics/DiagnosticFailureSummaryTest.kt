package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticFailureSummaryTest {
    @Test fun failedRunSupersedesItsUnfinishedTaskProgressAndKeepsPartialEvidence() {
        val progress = DiagnosticFailureEvidence(
            "verification",
            "VERIFICATION_CURRENT",
            Json.parseToJsonElement("""{"task_state":"RUNNING","task_outcome":"PARTIAL","completion_verified":false}""").jsonObject
        )
        val failed = summary(null, listOf(progress))
        assertEquals(JsonPrimitive("FAILED"), failed["latest_verdict"])
        assertEquals(JsonPrimitive("FAILED"), failed["task_state"])
        assertEquals(JsonPrimitive("RUNNING"), failed["last_recorded_task_state"])
        assertEquals(JsonPrimitive("PARTIAL"), failed["task_outcome"])
        val sources = failed.getValue("field_sources").jsonObject
        assertEquals(JsonPrimitive("AGENT_RUN"), sources["task_state"])
        assertEquals(JsonPrimitive("VERIFICATION_CURRENT"), sources["last_recorded_task_state"])

        val running = diagnosticFailureSummary("run", "RUNNING", null, "test", 141, "build", listOf(progress))
        assertEquals(JsonPrimitive("RUNNING"), running["task_state"])
        assertFalse(running.containsKey("last_recorded_task_state"))
    }

    @Test fun durableEngineeringRejectionKeepsSanitizedReasonFieldsAndSourceInCompactSummary() {
        val original = ProfileFailureRecord.fromProvider(
            "Private gateway details",
            "ENGINEERING_STATE_OR_INPUT_CHANGED",
            buildJsonObject {
                put("reasonCode", "ENGINEERING_SOURCE_REVISION_REQUIRED")
                put("invalid_fields", JsonArray(listOf(JsonPrimitive("source_sha"), JsonPrimitive("private-token"))))
                put("failed_stage", "request_validation")
                put("dispatched", false)
            }
        )
        val restored = Json.decodeFromString<ProfileFailureRecord>(Json.encodeToString(ProfileFailureRecord.serializer(), original))
        val timeline = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "NOTICE")
                    put("modelAuthored", false)
                    put("profileFailure", Json.encodeToJsonElement(ProfileFailureRecord.serializer(), restored))
                }
            )
        )
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(17, timeline))
        val result = summary(null, listOf(evidence))
        assertEquals(JsonPrimitive("ENGINEERING_STATE_OR_INPUT_CHANGED"), result["failure_code"])
        assertEquals(JsonPrimitive("ENGINEERING_SOURCE_REVISION_REQUIRED"), result["reason_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("source_sha"))), result["invalid_fields"])
        assertEquals(JsonPrimitive("MESSAGE_PROFILE_FAILURE"), result.getValue("field_sources").jsonObject["reason_code"])
        assertEquals(JsonPrimitive(false), result["dispatched"])
        assertFalse(result.toString().contains("private", ignoreCase = true))
        val completed = diagnosticFailureSummary("run", "COMPLETED", null, "test", 141, "build", listOf(evidence))
        assertFalse(completed.containsKey("reason_code"))
        assertFalse(completed.containsKey("invalid_fields"))
    }

    @Test fun latestLocalRecoveryFailureDoesNotBorrowTheOriginalProviderInvocation() {
        val original = buildJsonObject {
            put("code", "429")
            put(
                "details",
                buildJsonObject {
                    put("http_status", 429)
                    put("invocation_id", "original-invocation")
                    put("dispatched", true)
                }
            )
        }
        val recovery = dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", original)
        val output = recovery.block(
            buildJsonObject {
                put("state", "WAITING_PROVIDER_LIMIT")
                put("failure_code", "429")
                put("failure_details", original.getValue("details"))
                put("resume_allowed", true)
            }
        )
        val verification = DiagnosticFailureEvidence(
            "verification",
            "VERIFICATION_CURRENT",
            buildJsonObject {
                put("verification_failure", "429")
                put("task_outcome", "PARTIAL")
                put("completion_verified", false)
                put("invocation_id", "original-invocation")
            }
        )
        val result = summary("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", listOf(verification, DiagnosticFailureEvidence("output", "OUTPUT_CHECKPOINT", output)))
        assertEquals(JsonPrimitive("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE"), result["failure_code"])
        assertEquals(JsonPrimitive("lifecycle"), result["failed_stage"])
        assertEquals(JsonPrimitive("BLOCKED"), result["task_outcome"])
        assertEquals(JsonPrimitive("REVIEW_SAVED_RECOVERY"), result["recovery_action"])
        assertEquals(JsonNull, result["invocation_id"])
        assertEquals(JsonPrimitive(false), result["dispatched"])
        assertEquals(original, result["original_interruption"])
        val olderTerminal = summary("PROVIDER_WAIT_CHECKPOINT_CHANGED", listOf(verification, DiagnosticFailureEvidence("output", "OUTPUT_CHECKPOINT", output)))
        assertEquals(JsonPrimitive("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE"), olderTerminal["failure_code"])
        assertEquals(JsonPrimitive("RECOVERY_CHECKPOINT"), olderTerminal.getValue("field_sources").jsonObject["failure_code"])
        assertEquals(JsonPrimitive(false), olderTerminal["dispatched"])
    }

    @Test fun terminalRecoveryGuardOutranksSavedRateLimitWithoutBorrowingItsInvocation() {
        val interruption = DiagnosticFailureEvidence(
            "current",
            "VERIFICATION_CURRENT",
            Json.parseToJsonElement("""{"failure_code":"429","task_state":"PARTIAL","task_outcome":"PARTIAL","failed_stage":"model_invocation","invocation_id":"old-provider-attempt","retryable":true,"request_replay_allowed":true,"remote_acceptance":"NO","dispatched":true}""").jsonObject
        )
        val waiting = DiagnosticFailureEvidence(
            "output",
            "OUTPUT_CHECKPOINT",
            Json.parseToJsonElement("""{"state":"WAITING_PROVIDER_LIMIT","resume_allowed":true,"failure_details":{"code":"429","invocation_id":"old-provider-attempt","failed_stage":"model_invocation","retry_after_ms":120000}}""").jsonObject
        )
        for (code in listOf("PROVIDER_WAIT_CHECKPOINT_CHANGED", "RESEARCH_RECOVERY_ACTION_CHANGED")) {
            val result = summary("An unexpected error occurred: $code; private provider text", listOf(interruption, waiting))
            assertEquals(JsonPrimitive(code), result["latest_verdict"])
            assertEquals(JsonPrimitive(code), result["failure_code"])
            assertEquals(JsonPrimitive(code), result["stop_code"])
            assertEquals(JsonPrimitive("FAILED"), result["task_state"])
            assertEquals(JsonPrimitive("429"), result["prior_interruption_code"])
            assertEquals(JsonPrimitive("TERMINAL_ERROR"), result.getValue("field_sources").jsonObject["failure_code"])
            assertEquals(JsonPrimitive("VERIFICATION_CURRENT"), result.getValue("field_sources").jsonObject["prior_interruption_code"])
            assertEquals(JsonPrimitive("lifecycle"), result["failed_stage"])
            assertEquals(JsonPrimitive("REVIEW_SAVED_RECOVERY"), result["recovery_action"])
            assertEquals(JsonNull, result["invocation_id"])
            assertFalse(result.containsKey("dispatched"))
            assertFalse(result.containsKey("retryable"))
            assertFalse(result.containsKey("request_replay_allowed"))
            assertFalse(result.containsKey("remote_acceptance"))
            assertFalse(result.toString().contains("private provider text"))
        }
    }

    @Test fun completedRunDoesNotReviveHistoricalRecoveryGuard() {
        val result = diagnosticFailureSummary("run", "COMPLETED", "PROVIDER_WAIT_CHECKPOINT_CHANGED", "test", 141, "build", emptyList())
        assertEquals(JsonPrimitive("COMPLETED"), result["latest_verdict"])
        assertEquals(JsonNull, result["failure_code"])
        assertFalse(result.containsKey("prior_interruption_code"))
    }

    @Test fun incidentalGuardWordsDoNotReplaceTheRecordedFailureOrItsInvocation() {
        val failure = DiagnosticFailureEvidence(
            "current",
            "VERIFICATION_CURRENT",
            Json.parseToJsonElement("""{"failure_code":"HTTP_ERROR","failed_stage":"model_invocation","invocation_id":"actual-provider-attempt","dispatched":true}""").jsonObject
        )
        for (code in listOf("PROVIDER_WAIT_CHECKPOINT_CHANGED", "RESEARCH_RECOVERY_ACTION_CHANGED")) {
            val result = summary("An unexpected error occurred: Provider mentioned $code in its response", listOf(failure))
            assertEquals(JsonPrimitive("HTTP_ERROR"), result["latest_verdict"])
            assertEquals(JsonPrimitive("model_invocation"), result["failed_stage"])
            assertEquals(JsonPrimitive("actual-provider-attempt"), result["invocation_id"])
            assertEquals(JsonPrimitive(true), result["dispatched"])
            assertFalse(result.containsKey("prior_interruption_code"))
            for (terminal in listOf(code, "$code: Reconcile the retained results", "An unexpected error occurred: $code")) {
                assertEquals(JsonPrimitive(code), summary(terminal, emptyList())["latest_verdict"])
            }
        }
    }

    @Test fun ownedMessageFailureRetainsTypedGuardWithoutInventingDispatchOrTestEvidence() {
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(14, Json.parseToJsonElement("""[{"type":"NOTICE","modelAuthored":false,"text":"private explanation","profileFailure":{"category":"ENGINEERING_STATE_OR_INPUT_CHANGED","phase":"engineering_guard","retryAllowed":false,"requestReplayAllowed":false}}]""")))
        val result = summary("private terminal explanation", listOf(evidence))
        assertEquals(JsonPrimitive("ENGINEERING_STATE_OR_INPUT_CHANGED"), result["failure_code"])
        assertEquals(JsonPrimitive("engineering_guard"), result["failed_stage"])
        assertEquals(JsonPrimitive(false), result["retryable"])
        assertEquals(JsonPrimitive(false), result["request_replay_allowed"])
        assertEquals(JsonPrimitive("MESSAGE_PROFILE_FAILURE"), result.getValue("field_sources").jsonObject["failure_code"])
        assertEquals(listOf(JsonPrimitive("messages_v2:14:profileFailure")), result.getValue("source_record_ids").jsonArray.toList())
        assertEquals(JsonNull, result["tested_sha"])
        assertEquals(JsonNull, result["invocation_id"])
        assertFalse(result.containsKey("dispatched"))
        assertFalse(result.toString().contains("private"))
    }

    @Test fun latestTypedMessagePreflightPreservesExplicitDispatchAndSingleRecovery() {
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(4, Json.parseToJsonElement("""[{"type":"NOTICE","profileFailure":{"category":"REQUEST_REJECTED"}},{"type":"NOTICE","profileFailure":{"category":"GATEWAY_TRANSPORT_INTERRUPTED","phase":"connection_preflight","dispatched":false,"remoteAcceptance":"NO","retryAllowed":false,"requestReplayAllowed":false,"dispatchState":"PRECHECK_REJECTED","invocationId":"attempt-2","recoveryActions":["RETRY_AFTER_CONFIGURATION_CHANGE"]}}]""")))
        val result = summary(null, listOf(evidence))
        assertEquals(JsonPrimitive("GATEWAY_TRANSPORT_INTERRUPTED"), result["failure_code"])
        assertEquals(JsonPrimitive("connection_preflight"), result["failed_stage"])
        assertEquals(JsonPrimitive(false), result["dispatched"])
        assertEquals(JsonPrimitive("NO"), result["remote_acceptance"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), result["dispatch_state"])
        assertEquals(JsonPrimitive("attempt-2"), result["invocation_id"])
        assertEquals(JsonPrimitive("RETRY_AFTER_CONFIGURATION_CHANGE"), result["recovery_action"])
        assertEquals(JsonPrimitive("MESSAGE_PROFILE_FAILURE"), result.getValue("field_sources").jsonObject["dispatched"])
    }

    @Test fun messageProjectionRejectsAuthoredOrMalformedEvidenceAndDoesNotChooseBetweenRecoveryActions() {
        assertEquals(null, diagnosticMessageFailureEvidence(1, Json.parseToJsonElement("""[{"type":"NOTICE","modelAuthored":true,"profileFailure":{"category":"REQUEST_REJECTED"}}]""")))
        assertEquals(null, diagnosticMessageFailureEvidence(1, Json.parseToJsonElement("""[{"type":"NOTICE","profileFailure":{"category":"private arbitrary text"}}]""")))
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(1, Json.parseToJsonElement("""[{"type":"NOTICE","profileFailure":{"category":"REQUEST_REJECTED","phase":"Private stage!","dispatched":"false","retryAllowed":"true","requestReplayAllowed":"false","remoteAcceptance":"private","invocationId":"https://private.invalid","recoveryActions":["SELECT_REF","CHANGE_REPOSITORY"]}}]""")))
        val result = summary(null, listOf(evidence))
        assertEquals(JsonPrimitive("REQUEST_REJECTED"), result["failure_code"])
        assertEquals(JsonNull, result["failed_stage"])
        assertEquals(JsonNull, result["invocation_id"])
        assertEquals(JsonNull, result["recovery_action"])
        assertFalse(result.containsKey("dispatched"))
        assertFalse(result.containsKey("retryable"))
        assertFalse(result.containsKey("request_replay_allowed"))
        assertFalse(result.containsKey("remote_acceptance"))
        assertFalse(result.toString().contains("private"))
    }

    @Test fun activeReviewKeepsCausalPrecedenceAndCompletedRunsExcludeMessageFailures() {
        val message = requireNotNull(diagnosticMessageFailureEvidence(14, Json.parseToJsonElement("""[{"type":"NOTICE","profileFailure":{"category":"REQUEST_REJECTED","phase":"provider_admission","invocationId":"message-attempt","dispatched":false,"recoveryActions":["SELECT_REF"]}}]""")))
        val active = summary(null, listOf(message, reviewFailure()))
        assertEquals(JsonPrimitive("COMPLETION_REPAIR_BUDGET_EXHAUSTED"), active["failure_code"])
        assertEquals(JsonNull, active["invocation_id"])
        assertEquals(JsonNull, active["failed_stage"])
        assertEquals(JsonNull, active["recovery_action"])
        assertFalse(active.containsKey("dispatched"))
        val completed = diagnosticFailureSummary("run", "COMPLETED", null, "test", 141, "build", listOf(message))
        assertEquals(JsonNull, completed["failure_code"])
        assertEquals(JsonArray(emptyList()), completed["source_record_ids"])
        val verified = summary(null, listOf(message, DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("completion_verified", true) })))
        assertEquals(JsonPrimitive("VERIFIED"), verified["latest_verdict"])
        assertEquals(JsonNull, verified["failure_code"])
        assertEquals(listOf(JsonPrimitive("current")), verified.getValue("source_record_ids").jsonArray.toList())
    }

    @Test fun typedToolResultFailureProvidesRecoveryWithoutInventingDispatchOrRevisionEvidence() {
        val summary = summary("CLIENT_TOOL_RESULTS_REQUIRED", emptyList())
        assertEquals("CLIENT_TOOL_RESULTS_REQUIRED", summary.getValue("failure_code").jsonPrimitive.content)
        assertEquals("RESUME_WITH_RETAINED_TOOL_RESULTS", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("TERMINAL_ERROR", summary.getValue("field_sources").jsonObject.getValue("failure_code").jsonPrimitive.content)
        assertEquals("FAILURE_CODE_POLICY", summary.getValue("field_sources").jsonObject.getValue("recovery_action").jsonPrimitive.content)
        assertEquals(JsonNull, summary["tested_sha"])
        assertEquals(JsonNull, summary["invocation_id"])
        assertFalse(summary.containsKey("dispatched"))
    }

    @Test fun recordedRecoveryAndOutputStageRemainDistinctFromCompactEventPhase() {
        val event = DiagnosticFailureEvidence(
            "event",
            "VERIFICATION_EVENT",
            buildJsonObject {
                put("phase", "INTERRUPTED")
                put("task_state", "BLOCKED")
                put("failure_code", "CONVERSATION_CONFLICT")
            }
        )
        val output = DiagnosticFailureEvidence(
            "output",
            "OUTPUT_CHECKPOINT",
            buildJsonObject {
                put(
                    "failure_details",
                    buildJsonObject {
                        put("failed_stage", "message_admission")
                        put("recovery_action", "ATTACH_TO_ACTIVE_JOB")
                        put("invocation_id", "attempt")
                    }
                )
            }
        )
        val summary = summary(null, listOf(event, output))
        assertEquals("message_admission", summary.getValue("failed_stage").jsonPrimitive.content)
        assertEquals("ATTACH_TO_ACTIVE_JOB", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("OUTPUT_CHECKPOINT", summary.getValue("field_sources").jsonObject.getValue("recovery_action").jsonPrimitive.content)
    }

    @Test fun arbitraryErrorTextDoesNotBecomeAnInventedFailureCodeOrRecovery() {
        val summary = summary("The provider returned private unstructured details", emptyList())
        assertEquals("FAILED", summary.getValue("latest_verdict").jsonPrimitive.content)
        assertEquals(JsonNull, summary["failure_code"])
        assertEquals(JsonNull, summary["recovery_action"])
        assertFalse(summary.toString().contains("private unstructured details"))
    }

    @Test fun activeReviewFailureOwnsItsInvocationAfterSuccessfulGeneration() {
        val review = reviewFailure(
            buildJsonObject {
                put("invocation_id", "review-attempt")
                put("failed_stage", "provider_generation")
                put("recovery_action", "CHANGE_REVIEW_CONFIGURATION")
            }
        )
        val summary = summary(null, listOf(successfulOutput(), review))
        assertEquals(JsonPrimitive("COMPLETION_REPAIR_BUDGET_EXHAUSTED"), summary["failure_code"])
        assertEquals(JsonPrimitive("review-attempt"), summary["invocation_id"])
        assertEquals(JsonPrimitive("provider_generation"), summary["failed_stage"])
        assertEquals(JsonPrimitive("CHANGE_REVIEW_CONFIGURATION"), summary["recovery_action"])
        assertEquals(JsonPrimitive("REVIEW_CHECKPOINT"), summary.getValue("field_sources").jsonObject["invocation_id"])
        assertEquals(JsonPrimitive("review"), summary.getValue("source_record_ids").jsonArray.first())
    }

    @Test fun missingReviewerProvenanceCannotBorrowSuccessfulPrimaryInvocationStageOrRecovery() {
        val summary = summary(null, listOf(successfulOutput(), reviewFailure()))
        assertEquals(JsonNull, summary["invocation_id"])
        assertEquals(JsonNull, summary["failed_stage"])
        assertEquals(JsonNull, summary["recovery_action"])
        assertEquals(JsonPrimitive("COMPLETION_REPAIR_BUDGET_EXHAUSTED"), summary["latest_verdict"])
    }

    @Test fun resolvedReviewCheckpointCannotOverrideNewerSuccessEvenWithRetainedFailureFields() {
        for ((state, reviewState) in listOf("REVIEWED" to "PASS", "COMPLETED" to "FAILED")) {
            val review = reviewFailure(
                buildJsonObject {
                    put("state", state)
                    put("review_state", reviewState)
                }
            )
            val summary = summary(null, listOf(review))
            assertEquals(JsonNull, summary["failure_code"])
            assertEquals(JsonPrimitive("FAILED"), summary["latest_verdict"])
            assertEquals(emptyList<Any>(), summary.getValue("source_record_ids").jsonArray.toList())
        }
        val verified = DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("completion_verified", true) })
        val summary = summary(null, listOf(reviewFailure(), verified))
        assertEquals(JsonPrimitive("VERIFIED"), summary["latest_verdict"])
        assertEquals(JsonNull, summary["failure_code"])
        assertEquals(listOf(JsonPrimitive("current")), summary.getValue("source_record_ids").jsonArray.toList())
    }

    @Test fun completedRunExcludesStaleUnresolvedReviewCheckpoint() {
        val summary = diagnosticFailureSummary("run", "COMPLETED", null, "test", 141, "build", listOf(reviewFailure()))
        assertEquals(JsonPrimitive("COMPLETED"), summary["latest_verdict"])
        assertEquals(JsonNull, summary["failure_code"])
    }

    @Test fun observedHeadCannotBecomeTestedRevisionAndDistinctExplicitRevisionIsPreserved() {
        val observed = "a".repeat(40)
        val tested = "b".repeat(40)
        val current = DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("head_sha", observed) })
        val untested = summary(null, listOf(current))
        assertEquals(JsonNull, untested["tested_sha"])
        assertEquals(JsonPrimitive("NOT_RETAINED"), untested["tested_sha_status"])
        assertEquals(JsonPrimitive(observed), untested["observed_head_sha"])
        val measured = summary(null, listOf(current.copy(payload = JsonObject(current.payload + ("tested_sha" to JsonPrimitive(tested))))))
        assertEquals(JsonPrimitive(tested), measured["tested_sha"])
        assertEquals(JsonPrimitive(observed), measured["observed_head_sha"])
    }

    private fun successfulOutput() = DiagnosticFailureEvidence(
        "output",
        "OUTPUT_CHECKPOINT",
        buildJsonObject {
            put("state", "COMPLETED")
            put("invocation_id", "successful-primary")
            put("failed_stage", "primary_stage_must_not_be_borrowed")
            put("recovery_action", "PRIMARY_RECOVERY_MUST_NOT_BE_BORROWED")
        }
    )

    private fun reviewFailure(extra: JsonObject = JsonObject(emptyMap())) = DiagnosticFailureEvidence(
        "review",
        "REVIEW_CHECKPOINT",
        JsonObject(
            buildJsonObject {
                put("state", "BLOCKED")
                put("review_state", "FAILED")
                put("delivery_state", "WITHHELD")
                put("failure_code", "COMPLETION_REPAIR_BUDGET_EXHAUSTED")
                put("review_stage", "answer_review")
            } + extra
        )
    )

    private fun summary(error: String?, evidence: List<DiagnosticFailureEvidence>): JsonObject =
        diagnosticFailureSummary("run", "FAILED", error, "test", 141, "build", evidence, capturedAtMillis = 1)
}
