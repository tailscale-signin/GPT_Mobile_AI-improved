package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "The observed failure")
    private fun response(request: Request, code: Int, body: String, retryAfter: String? = null) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .apply { retryAfter?.let { header("Retry-After", it) } }.body(body.toResponseBody("application/json".toMediaType())).build()
    private fun body(request: Request) = Buffer().also { request.body?.writeTo(it) }.readUtf8()

    @Test fun newDiagnosticEvidenceRequiresMatchingServiceBeforePostingTheUnchangedApprovedPayload() {
        for ((feature, evidence) in listOf(
            "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1" to mapOf("required_schema_tokens" to 5000, "available_schema_tokens" to 0),
            "CAPACITY_ADMISSION_EVIDENCE_V1" to mapOf("admission_capacity_provenance" to "VERIFIED_ROUTE"),
            "CONVERSATION_CONFLICT_EVIDENCE_V1" to mapOf("conflict_pending_tool_state" to "NOT_REPORTED"),
            "ACCEPTANCE_SCOPE_EVIDENCE_V1" to mapOf("acceptance_capability_scope" to "FULL_CATALOG", "acceptance_pagination_mode" to "BOUNDED_SMOKE_SAMPLE"),
            "GITHUB_PREREQUISITE_EVIDENCE_V1" to mapOf("repository_graph_observation" to "NOT_INDEXED", "graph_input_blocked_count" to 16, "workflow_dependency_observation" to "MISSING_EXECUTED_JOB"),
            "ACCEPTANCE_RECOVERY_EVIDENCE_V1" to mapOf("status_policy_protection_http_status" to 404, "status_policy_complete" to false),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("branch_protection_http_status" to 404),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("branch_rules_http_status" to 403),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("status_policy_recovery" to "CHECK_RULES_READ_PERMISSION"),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("unmet_criterion_blockers" to listOf("GRAPH_INDEX_REQUIRED")),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("unmet_criterion_blockers" to listOf("GRAPH_INDEX_UNCONFIRMED")),
            "GITHUB_GRAPH_POLICY_EVIDENCE_V1" to mapOf("unmet_criterion_blockers" to listOf("GRAPH_INPUT_REQUIRED")),
            "VOICE_INSTALL_EVIDENCE_V1" to mapOf("voice_install_pending_reason" to "CONNECTIVITY"),
            "VOICE_INSTALL_EVIDENCE_V1" to mapOf("voice_install_failure" to "WORK_NOT_FOUND"),
            "CAPTURE_BOUNDARY_EVIDENCE_V1" to mapOf("incident_capture_site" to "PROVIDER_ADAPTER_STREAM_CATCH", "incident_phase_provenance" to "CAPTURE_BOUNDARY_ONLY"),
            "CAPTURE_BOUNDARY_EVIDENCE_V1" to mapOf("incident_capture_site" to "CHAT_REPOSITORY_ERROR_VALUE", "incident_phase_provenance" to "CAPTURE_BOUNDARY_ONLY")
        )) {
            val area = when (feature) {
                "ACCEPTANCE_SCOPE_EVIDENCE_V1", "GITHUB_PREREQUISITE_EVIDENCE_V1", "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1" -> "ACCEPTANCE"
                "VOICE_INSTALL_EVIDENCE_V1" -> "VOICE"
                else -> "PROVIDER"
            }
            val context = IssueReportContext.fromFields(if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE", evidence + ("diagnostic_area" to area), 1_000L)
            val requiredFeatures = requireNotNull(context.diagnostics).requiredServiceFeatures()
            assertTrue(feature in requiredFeatures)
            val enriched = report.copy(context = context)
            val payload = enriched.approvedPayload()
            var supported = false
            var dispatched = 0
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    val features = JsonArray(requiredFeatures.filter { supported || it != feature }.map(::JsonPrimitive)).toString()
                    response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, enriched.approvedPayload())
            supported = true
            assertEquals(IssueReportSubmission.Confirmed(941), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
    }

    @Test fun schemaBudgetCodeRequiresServiceSupportEvenWithoutCountEvidence() {
        val payload = report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = "GITHUB_TOOL_CONTEXT_BUDGET").approvedPayload()
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":[]}""")
        }.build()
        val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", outcome.code)
        assertEquals(listOf("GET"), methods)
    }

    @Test fun schemaBudgetDiagnosticCodesWaitForServiceSupportWithoutChangingApprovedBytes() {
        val feature = "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1"
        for ((area, field) in listOf("PROVIDER" to "provider_failure_code", "INCIDENT" to "causal_failure_code", "VOICE" to "voice_chat_failure_code")) {
            val context = IssueReportContext.fromFields(
                if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE",
                mapOf("diagnostic_area" to area, field to "GITHUB_TOOL_CONTEXT_BUDGET"),
                1_000L
            )
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive("GITHUB_TOOL_CONTEXT_BUDGET"), diagnostic.values[field])
            val approved = report.copy(context = context)
            val payload = approved.approvedPayload()
            var supported = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    val features = JsonArray(diagnostic.requiredServiceFeatures().filter { supported || it != feature }.map(::JsonPrimitive))
                    response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ }
            assertTrue("$area/$field must wait before posting: $methods", blocked is IssueReportSubmission.Failure)
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (blocked as IssueReportSubmission.Failure).code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            supported = true
            assertEquals(IssueReportSubmission.Confirmed(941), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
    }

    @Test fun recoveryAndProtocolCodesWaitForServiceSupportInEveryAllowedReportLocation() {
        val recoveryCodes = listOf(
            "TOOL_BUDGET_EXHAUSTED",
            "VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED",
            "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED",
            "SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED",
            "VALIDATION_REVISION_UNAVAILABLE", "VALIDATION_RUNTIME_UNAVAILABLE", "ENGINEERING_REPOSITORY_ACCESS_REQUIRED"
        )
        val locations = listOf("errorCode", "originalFailureCode", "events", "provider_failure_code", "causal_failure_code", "voice_chat_failure_code")
        var retainedFixtures = 0
        for (code in recoveryCodes + "TOOL_PROTOCOL_INVALID_FRAME") {
            val feature = if (code in recoveryCodes) "ISSUE_RECOVERY_CODES_V1" else "COMPLETION_EVIDENCE_V1"
            for (location in locations) {
                val context = when (location) {
                    "errorCode", "events" -> null
                    "originalFailureCode" -> IssueReportContext.fromFields(
                        "CHAT_FAILURE",
                        mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "original_interruption" to mapOf("code" to code)),
                        1_000L
                    ).also { assertEquals(code, it.originalFailureCode) }
                    else -> {
                        val area = when (location) {
                            "provider_failure_code" -> "PROVIDER"
                            "voice_chat_failure_code" -> "VOICE"
                            else -> "INCIDENT"
                        }
                        IssueReportContext.fromFields(if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE", mapOf("diagnostic_area" to area, location to code), 1_000L)
                    }
                }
                if (location == "provider_failure_code" && code in recoveryCodes) {
                    assertFalse(requireNotNull(context?.diagnostics).values.containsKey(location))
                    continue
                }
                if (location.endsWith("_failure_code")) assertEquals(JsonPrimitive(code), context?.diagnostics?.values?.get(location))
                val approved = when (location) {
                    "errorCode" -> report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = code)
                    "events" -> report.copy(events = listOf("-1ms E tools $code"))
                    else -> report.copy(context = context)
                }
                val payload = approved.approvedPayload()
                val requiredFeatures = context?.diagnostics?.requiredServiceFeatures().orEmpty() + feature
                var supported = false
                var dispatched = 0
                val methods = mutableListOf<String>()
                val posted = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        val features = JsonArray(requiredFeatures.filter { supported || it != feature }.map(::JsonPrimitive))
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                    }
                }.build()
                val submission = IssueReportSubmission(origin, client)
                val blocked = submission.send(report.reportId, payload) { dispatched++ }
                assertTrue("$code at $location must wait before dispatch: $methods", blocked is IssueReportSubmission.Failure)
                blocked as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                assertTrue(blocked.retryable)
                assertFalse(blocked.automaticRetry)
                assertEquals(listOf("GET"), methods)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                assertEquals(payload, approved.approvedPayload())
                supported = true
                assertEquals(IssueReportSubmission.Confirmed(941), submission.send(report.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "GET", "POST"), methods)
                assertEquals(1, dispatched)
                assertEquals(listOf(payload), posted)
                retainedFixtures++
            }
        }
        assertEquals(61, retainedFixtures)
    }

    @Test fun establishedFailureCodesRemainDeliverableWithoutNewServiceCapabilities() {
        for (code in listOf("TOOL_FAILURE", "ENGINEERING_STATE_OR_INPUT_CHANGED")) {
            val approved = report.copy(source = "debug", title = null, description = null, feature = "tools", errorCode = code, events = listOf("-1ms E tools $code"))
            val payload = approved.approvedPayload()
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
            }.build()
            assertEquals(IssueReportSubmission.Confirmed(941), IssueReportSubmission(origin, client).send(report.reportId, payload))
            assertEquals(listOf("POST"), methods)
            assertEquals(listOf(payload), posted)
        }
    }

    @Test fun preparingVoiceRequiresServiceSupportBeforePostingApprovedBytes() {
        val context = IssueReportContext.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "VOICE", "voice_engine" to "KOKORO", "voice_phase" to "PREPARING"), 1_000L)
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var supported = false
        var dispatched = 0
        val posted = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                val features = if (supported) "[\"VOICE_PLAYBACK_PHASE_V1\"]" else "[]"
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
        assertFalse(blocked.automaticRetry)
        assertEquals(0, dispatched)
        assertTrue(posted.isEmpty())
        assertEquals(payload, enriched.approvedPayload())
        supported = true
        assertEquals(IssueReportSubmission.Confirmed(941), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(1, dispatched)
        assertEquals(listOf(payload), posted)
    }

    @Test fun changedPolicyFieldsRequireTheirOwnCapabilityAndKeepApprovedBytes() {
        for (code in listOf("CHAT_FAILED", "DISPATCH_POLICY_CHANGED")) {
            val context = IssueReportContext.fromFields(
                "CHAT_FAILURE",
                IssueReportIncidentCapsule.fields(
                    ReportFeature.CHAT,
                    "CHAT_FAILURE",
                    code,
                    mapOf("changed_policy_fields" to listOf("profile.token", "providerPermissions"), "failed_stage" to "tool_dispatch", "resume_required" to true)
                ),
                1_000L
            )
            val enriched = report.copy(context = context)
            val payload = enriched.approvedPayload()
            var features = "[\"DISPATCH_POLICY_DIAGNOSTICS_V1\"]"
            val posted = mutableListOf<String>()
            var dispatched = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ }
            assertTrue(blocked is IssueReportSubmission.Failure)
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (blocked as IssueReportSubmission.Failure).code)
            assertTrue(blocked.retryable)
            assertFalse(blocked.automaticRetry)
            assertTrue(posted.isEmpty())
            assertEquals(0, dispatched)
            assertEquals(payload, enriched.approvedPayload())
            features = "[\"DISPATCH_POLICY_DIAGNOSTICS_V1\",\"CHANGED_POLICY_FIELDS_V1\"]"
            assertEquals(IssueReportSubmission.Confirmed(941), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf(payload), posted)
            assertEquals(1, dispatched)
            assertEquals(payload, enriched.approvedPayload())
        }
    }

    @Test fun incidentCapsuleRequiresSevenAndNeverDowngradesTheApprovedPayloadForAnOlderService() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", mapOf("run_id" to "owned-run")),
            1_000L
        )
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var versions = "[1,2,3,4,5,6]"
        val posted = mutableListOf<String>()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":$versions}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        for (unsupported in listOf("[1,2,3,4,5,6]", "[1,2,3,4,5,6,\"7\"]", "[1,2,3,4,5,6,true]")) {
            versions = unsupported
            val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
            assertTrue(failure.retryable)
            assertFalse(failure.automaticRetry)
            assertTrue(posted.isEmpty())
            assertEquals(0, dispatched)
            assertEquals(payload, enriched.approvedPayload())
        }
        versions = "[1,2,3,4,5,6,7]"
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(listOf(payload), posted)
        assertEquals(1, dispatched)
    }

    @Test fun engineeringContextRequiresSixBeforeDispatchAndKeepsTheExactApprovedPayload() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("reasonCode" to "ENGINEERING_BUDGET_INVALID", "invalid_fields" to listOf("execution_budget"), "failed_stage" to "request_validation"), 1_000L)
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var versions = "[1,2,3,4,5]"
        val posted = mutableListOf<String>()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":$versions}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        for (unsupported in listOf("[1,2,3,4,5]", "[1,2,3,4,5,\"6\"]", "[1,2,3,4,5,true]")) {
            versions = unsupported
            val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
            assertFalse(failure.automaticRetry)
            assertTrue(posted.isEmpty())
            assertEquals(0, dispatched)
        }
        versions = "[1,2,3,4,5,6]"
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(listOf(payload), posted)
        assertEquals(1, dispatched)
        assertEquals(payload, enriched.approvedPayload())
    }

    @Test fun additionalDiagnosticsRequireFiveAndArePostedWithoutChangingAnyApprovedByte() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER", "model_hash" to "a".repeat(64), "output_tokens" to 4096, "finish_reason" to "LENGTH"), 1_000L)
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var versions = "[1,2,3,4]"
        val posted = mutableListOf<String>()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":$versions}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        for (unsupported in listOf("[1,2,3,4]", "[1,2,3,4,\"5\"]", "[1,2,3,4,true]")) {
            versions = unsupported
            val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
            assertTrue(failure.retryable)
            assertFalse(failure.automaticRetry)
            assertEquals(payload, enriched.approvedPayload())
            assertTrue(posted.isEmpty())
            assertEquals(0, dispatched)
        }
        versions = "[1,2,3,4,5]"
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(listOf(payload), posted)
        assertEquals(1, dispatched)
    }

    @Test fun newIncidentFieldsWaitForReporterSupportBeforePostingApprovedBytes() {
        val context = IssueReportContext.fromFields("TOOL_RESULT", mapOf("diagnostic_area" to "TOOL", "tool_action" to "headers", "unknown_field_count" to 1), 1_000L)
        assertEquals(7, context.schemaVersion)
        val payload = report.copy(context = context).approvedPayload()
        var supported = false
        val posted = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                val features = if (supported) "[\"INCIDENT_FAILURE_DETAILS_V1\"]" else "[]"
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":922}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val rejected = submission.send(report.reportId, payload) as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", rejected.code)
        assertFalse(rejected.automaticRetry)
        assertTrue(posted.isEmpty())
        supported = true
        assertEquals(IssueReportSubmission.Confirmed(922), submission.send(report.reportId, payload))
        assertEquals(listOf(payload), posted)
    }

    @Test fun admissionAndModelPreparationRequireBothVersionAndFeatureWithoutRewritingApproval() {
        val context = IssueReportContext.fromFields(
            "STRUCTURED_EVENT",
            mapOf(
                "diagnostic_area" to "GATEWAY",
                "gateway_manifest_hash" to "a".repeat(64),
                "gateway_admission_ready" to false,
                "model_preparation_state" to "LOAD_REJECTED",
                "gateway_upstream_status" to 403
            ),
            1_000L
        )
        assertEquals(6, context.schemaVersion)
        assertEquals(2, context.diagnostics?.schemaVersion)
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var versions = "[1,2,3,4,5]"
        var features = "[\"GATEWAY_MODEL_PREPARATION_V1\"]"
        val posted = mutableListOf<String>()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":$versions,"reportDiagnosticFeatures":$features}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":886}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        for ((supportedVersions, supportedFeatures) in listOf(versions to features, "[1,2,3,4,5,6]" to "[]")) {
            versions = supportedVersions
            features = supportedFeatures
            val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
            assertFalse(failure.automaticRetry)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, enriched.approvedPayload())
        }
        versions = "[1,2,3,4,5,6]"
        features = "[\"GATEWAY_MODEL_PREPARATION_V1\"]"
        assertEquals(IssueReportSubmission.Confirmed(886), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(listOf(payload), posted)
        assertEquals(1, dispatched)
    }

    @Test fun modelPreparationRequiresAdvertisedFeatureBeforePostingTheOriginalApprovedPayload() {
        val context = IssueReportContext.fromFields("STRUCTURED_EVENT", mapOf("diagnostic_area" to "GATEWAY", "model_preparation_state" to "LOAD_REJECTED", "gateway_upstream_status" to 403), 1_000L)
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        var features = "null"
        val posted = mutableListOf<String>()
        var dispatched = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5],"reportDiagnosticFeatures":$features}""")
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":884}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        for (unsupported in listOf("null", "[]", "[true]", "[\"UNRELATED_FEATURE\"]", "\"GATEWAY_MODEL_PREPARATION_V1\"")) {
            features = unsupported
            val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
            assertTrue(failure.retryable)
            assertFalse(failure.automaticRetry)
            assertEquals(payload, enriched.approvedPayload())
            assertTrue(posted.isEmpty())
            assertEquals(0, dispatched)
        }
        features = "[\"GATEWAY_MODEL_PREPARATION_V1\"]"
        assertEquals(IssueReportSubmission.Confirmed(884), submission.send(report.reportId, payload) { dispatched++ })
        assertEquals(listOf(payload), posted)
        assertEquals(1, dispatched)
    }

    @Test fun reviewCodesRequireTheirFeatureInErrorOriginalFailureAndExactEventsIncludingWithoutContext() {
        for (code in listOf("REVIEW_INPUT_REQUIRED", "GITHUB_REVIEW_COMPARISON_REQUIRED")) {
            val retained = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "original_interruption" to mapOf("code" to code)), 1_000L)
            assertEquals(code, retained.originalFailureCode)
            for (enriched in listOf(report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = code), report.copy(context = retained), report.copy(events = listOf("-1ms E tools $code")))) {
                val payload = enriched.approvedPayload()
                var supported = false
                val posted = mutableListOf<String>()
                var dispatched = 0
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    if (request.method == "GET") {
                        val feature = if (supported) "REVIEW_COMPARISON_V1" else "GATEWAY_MODEL_PREPARATION_V1"
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5],"reportDiagnosticFeatures":["$feature"]}""")
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":885}""")
                    }
                }.build()
                val submission = IssueReportSubmission(origin, client)
                val failure = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
                assertFalse(failure.automaticRetry)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                supported = true
                assertEquals(IssueReportSubmission.Confirmed(885), submission.send(report.reportId, payload) { dispatched++ })
                assertEquals(listOf(payload), posted)
                assertEquals(1, dispatched)
                assertEquals(payload, enriched.approvedPayload())
            }
        }
    }

    @Test fun reviewCodeInUserDescriptionDoesNotInventARequiredFeature() {
        val payload = report.copy(description = "REVIEW_INPUT_REQUIRED appeared previously", events = listOf("-1ms E tools CHAT_FAILED")).approvedPayload()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("POST", chain.request().method)
            response(chain.request(), 200, """{"reportId":"${report.reportId}","number":885}""")
        }.build()
        assertEquals(IssueReportSubmission.Confirmed(885), IssueReportSubmission(origin, client).send(report.reportId, payload))
    }

    @Test fun privateFieldsInjectedIntoSavedDiagnosticJsonAreRejectedBeforeAnyNetworkCall() {
        val enriched = report.copy(context = IssueReportContext.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "VOICE", "voice_engine" to "SYSTEM"), 1_000L))
        val raw = IssueReport.json.parseToJsonElement(enriched.approvedPayload()).jsonObject
        val context = raw.getValue("context").jsonObject
        val diagnostics = context.getValue("diagnostics").jsonObject
        val client = OkHttpClient.Builder().addInterceptor { error("Invalid data must never leave the device") }.build()
        val invalid = listOf(
            JsonObject(diagnostics + ("transcript" to JsonPrimitive("private-transcript"))),
            JsonObject(diagnostics + ("values" to JsonObject(diagnostics.getValue("values").jsonObject + ("voice_read_calls" to JsonPrimitive("12")))))
        )
        for (capture in invalid) {
            val payload = JsonObject(raw + ("context" to JsonObject(context + ("diagnostics" to capture)))).toString()
            val failure = IssueReportSubmission(origin, client).send(report.reportId, payload) { error("Invalid approval cannot dispatch") } as IssueReportSubmission.Failure
            assertEquals("REPORT_PAYLOAD_INVALID", failure.code)
            assertFalse(failure.retryable)
        }
    }

    @Test fun lostReceiptRetriesTheExactApprovedPayloadAndAcceptsBoundConfirmation() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += body(chain.request())
            assertEquals("$origin/v1/issue-reports", chain.request().url.toString())
            if (requests.size == 1) throw IOException("Receipt lost after acceptance")
            response(chain.request(), 200, """{"reportId":"${report.reportId}","number":742}""")
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val approved = report.approvedPayload()
        assertTrue(submission.send(report.reportId, approved) is IssueReportSubmission.Failure)
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved))
        assertEquals(listOf(approved, approved), requests)
    }

    @Test fun legacyReceiptsRemainValidButExplicitWrongIdentityNeverConfirmsSuccess() {
        for ((receipt, confirmed) in listOf("""{"number":742}""" to true, """{"reportId":"other-report","number":742}""" to false, """{"reportId":null,"number":742}""" to false, """{"reportId":42,"number":742}""" to false, """{"number":"742"}""" to false, """{"number":0}""" to false, "not JSON" to false)) {
            val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 200, receipt) }.build()
            val result = IssueReportSubmission(origin, client).send(report.reportId, report.approvedPayload())
            assertEquals(receipt, confirmed, result is IssueReportSubmission.Confirmed)
            if (!confirmed) assertTrue((result as IssueReportSubmission.Failure).retryable)
        }
    }

    @Test fun newContextWaitsForDeclaredServiceSupportWithoutDowngradingApprovedBytes() {
        val enriched = report.copy(context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = 1_000L))
        val payload = enriched.approvedPayload()
        val methods = mutableListOf<String>()
        val posted = mutableListOf<String>()
        var readiness = """{"service":"gpt-mobile-issue-reporter","version":1}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "GET") {
                assertEquals("$origin/health/ready", request.url.toString())
                response(request, 200, readiness)
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val stopped = submission.send(report.reportId, payload) as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", stopped.code)
        assertTrue(stopped.retryable)
        assertFalse(stopped.automaticRetry)
        assertEquals(listOf("GET"), methods)
        readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":["1"]}"""
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (submission.send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        assertTrue(posted.isEmpty())
        readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":[1]}"""
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, payload))
        assertEquals(listOf("GET", "GET", "GET", "POST"), methods)
        assertEquals(listOf(payload), posted)
    }

    @Test fun contextualReportRequiresItsIdentityInTheReceiptBeforeConfirming() {
        val enriched = report.copy(context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = 1_000L))
        val approved = enriched.approvedPayload()
        val posted = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1]}""")
            } else {
                posted += body(request)
                val receipt = if (posted.size == 1) """{"number":742}""" else """{"reportId":"${report.reportId}","number":742}"""
                response(request, 200, receipt)
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val uncertain = submission.send(report.reportId, approved) as IssueReportSubmission.Failure
        assertEquals("REPORT_RECEIPT_MISMATCH", uncertain.code)
        assertTrue(uncertain.retryable)
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved))
        assertEquals(listOf(approved, approved), posted)
    }

    @Test fun acceptanceContextWaitsForVersionTwoWithoutDispatchingOrChangingApprovedEvidence() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "failed_stage" to "acceptance_verification",
                "stop_reason" to "UNVERIFIED_ACCEPTANCE_CRITERIA",
                "required_criteria" to 5,
                "verified_criteria" to 4,
                "remaining_criteria" to 1
            ),
            occurredAtMillis = 1_000L
        )
        assertEquals(2, context.schemaVersion)
        val approved = report.copy(context = context).approvedPayload()
        val posted = mutableListOf<String>()
        var dispatchMarkers = 0
        var readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":[1]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, readiness)
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val stopped = submission.send(report.reportId, approved) { dispatchMarkers++ } as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", stopped.code)
        assertTrue(stopped.retryable)
        assertFalse(stopped.automaticRetry)
        assertEquals(0, dispatchMarkers)
        assertTrue(posted.isEmpty())
        readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":[1,2]}"""
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved) { dispatchMarkers++ })
        assertEquals(1, dispatchMarkers)
        assertEquals(listOf(approved), posted)
    }

    @Test fun freshAndRetainedReviewContextsWaitForVersionThreeWithoutChangingApprovedBytes() {
        for (fields in listOf(mapOf("failed_stage" to "review_comparison"), mapOf("failed_stage" to "approval"), mapOf("next_action" to "SELECT_REVIEW_BASELINE"), mapOf("next_action" to "RESTORE_REVIEW_COMPARISON_EVIDENCE"))) {
            val fresh = IssueReportContext.fromFields("CHAT_FAILURE", fields, occurredAtMillis = 1_000L)
            assertEquals(3, fresh.schemaVersion)
            for (wireVersion in listOf(2, 3)) {
                val approved = report.copy(context = fresh.copy(schemaVersion = wireVersion)).approvedPayload()
                val posted = mutableListOf<String>()
                var dispatchMarkers = 0
                var supported = "[1,2]"
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    if (request.method == "GET") {
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":$supported}""")
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
                    }
                }.build()
                val submission = IssueReportSubmission(origin, client)
                val incompatible = if (wireVersion == 2) listOf("[1,2]", "[3]", "[1,3]", "[1,2,\"3\"]") else listOf("[1,2]", "[1,2,\"3\"]")
                for (versions in incompatible) {
                    supported = versions
                    val stopped = submission.send(report.reportId, approved) { dispatchMarkers++ } as IssueReportSubmission.Failure
                    assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", stopped.code)
                    assertTrue(stopped.retryable)
                    assertFalse(stopped.automaticRetry)
                    assertEquals(0, dispatchMarkers)
                    assertTrue(posted.isEmpty())
                }
                supported = if (wireVersion == 2) "[1,2,3]" else "[3]"
                assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved) { dispatchMarkers++ })
                assertEquals(1, dispatchMarkers)
                assertEquals(listOf(approved), posted)
            }
        }
    }

    @Test fun declaredVersionThreeSupportDoesNotMakeInvalidReportsRetryable() {
        val approved = report.copy(context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("failed_stage" to "review_comparison"), occurredAtMillis = 1_000L)).approvedPayload()
        val posted = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3]}""")
            } else {
                posted += body(request)
                response(request, 422, """{"error":"INVALID_REPORT"}""")
            }
        }.build()
        val failure = IssueReportSubmission(origin, client).send(report.reportId, approved) as IssueReportSubmission.Failure
        assertEquals("INVALID_REPORT", failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.automaticRetry)
        assertEquals(listOf(approved), posted)
    }

    @Test fun versionFourRecoveryAndPrecheckReportsWaitBeforeDispatchAndRetryTheirExactBytes() {
        for ((source, fields) in listOf(
            "CHAT_FAILURE" to mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_INPUT_CHANGED", "failed_stage" to "lifecycle", "next_action" to "REVIEW_SAVED_RECOVERY", "dispatched" to false),
            "CHAT_FAILURE" to mapOf("failed_stage" to "claim_extraction", "reason_code" to "GITHUB_EXTRACTION_TIMEOUT", "next_action" to "RETRY_CLAIM_EXTRACTION_FROM_SAVED_RECEIPTS"),
            "TOOL_RESULT" to mapOf("failed_stage" to "tool_precheck", "reason_code" to "DUPLICATE_INVOCATION", "dispatched" to false)
        )) {
            val context = IssueReportContext.fromFields(source, fields, occurredAtMillis = 1_000L)
            assertEquals(4, context.schemaVersion)
            val approved = report.copy(context = context).approvedPayload()
            val posted = mutableListOf<String>()
            var dispatchMarkers = 0
            var supported = "[1,2,3]"
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":$supported}""")
                } else {
                    posted += body(request)
                    if (posted.size == 1) throw IOException("Receipt unavailable after dispatch")
                    response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            for (versions in listOf("[1,2,3]", "[1,2,3,\"4\"]", "[1,2,3,true]")) {
                supported = versions
                val stopped = submission.send(report.reportId, approved) { dispatchMarkers++ } as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", stopped.code)
                assertTrue(stopped.retryable)
                assertFalse(stopped.automaticRetry)
                assertEquals(0, dispatchMarkers)
                assertTrue(posted.isEmpty())
            }
            supported = "[1,2,3,4]"
            val uncertain = submission.send(report.reportId, approved) { dispatchMarkers++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_CONNECTION_FAILED", uncertain.code)
            assertTrue(uncertain.retryable)
            assertEquals(1, dispatchMarkers)
            assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved) { dispatchMarkers++ })
            assertEquals(2, dispatchMarkers)
            assertEquals(listOf(approved, approved), posted)
        }
    }

    @Test fun transientAndPermanentRejectionsHaveBoundedSafeMetadataAndRetryDeadlines() {
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 429, """{"error":"RATE_LIMITED","private":"must not be surfaced"}""", "120") }.build()
        val failure = IssueReportSubmission(origin, client) { 10_000L }.send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals(429, failure.httpStatus)
        assertEquals(130_000L, failure.retryNotBeforeMillis)
        assertTrue(failure.retryable)
        val rejected = OkHttpClient.Builder().addInterceptor { response(it.request(), 422, """{"error":"INVALID_REPORT"}""") }.build()
        val permanent = IssueReportSubmission(origin, rejected).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
        assertFalse(permanent.retryable)
        assertEquals("INVALID_REPORT", permanent.code)
        val unsafe = OkHttpClient.Builder().addInterceptor { response(it.request(), 403, """{"error":"private arbitrary response"}""") }.build()
        assertEquals("REPORT_HTTP_403", (IssueReportSubmission(origin, unsafe).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure).code)
    }

    @Test fun unconfiguredUnsafeOriginAndCorruptPayloadNeverDispatch() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            response(it.request(), 200, """{"number":742}""")
        }.build()
        for (invalid in listOf("", "http://reports.example.invalid", "$origin/path", "https://user:secret@reports.example.invalid", "$origin?other=1")) {
            val result = IssueReportSubmission(invalid, client).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UNCONFIGURED", result.code)
            assertFalse(result.automaticRetry)
        }
        for (payload in listOf("not JSON", "{}", report.approvedPayload().replace(report.reportId, "2e0ef31f-f454-4785-8d93-16d538fd1168"))) {
            assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        }
        assertEquals(0, calls)
    }

    @Test fun readinessFailuresDoNotMarkAReportAsPossiblySubmitted() {
        val enriched = report.copy(context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = 1000L))
        var writes = 0
        var readiness = """{"service":"other-service","reportContextVersions":[1]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "GET") response(chain.request(), 200, readiness) else response(chain.request(), 200, """{"reportId":"${report.reportId}","number":742}""")
        }.build()
        val submission = IssueReportSubmission(origin, client)
        assertEquals("REPORT_SERVICE_UNAVAILABLE", (submission.send(report.reportId, enriched.approvedPayload()) { writes++ } as IssueReportSubmission.Failure).code)
        assertEquals(0, writes)
        readiness = """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1]}"""
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, enriched.approvedPayload()) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun durableDispatchFailureStopsBeforeAnyPost() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            response(it.request(), 200, """{"number":742}""")
        }.build()
        val result = runCatching { IssueReportSubmission(origin, client).send(report.reportId, report.approvedPayload()) { error("Cannot persist dispatch marker") } }
        assertTrue(result.isFailure)
        assertEquals(0, requests)
    }
}
