package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptancePublicationEvidence
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityFailureEvidence
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    // Exact legacy endpoint feature inventory. The new capability is negotiated
    // separately so the existing 1 KiB response remains compatible.
    private val legacyReadyFeatures = listOf(
        "GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1", "INCIDENT_FAILURE_DETAILS_V1", "DISPATCH_POLICY_DIAGNOSTICS_V1",
        "CHANGED_POLICY_FIELDS_V1", "EXECUTION_OUTCOME_DIAGNOSTICS_V1", "RUN_FAILURE_EVIDENCE_V1", "RECOVERY_OBSERVATIONS_V1",
        "ISSUE_RECOVERY_DETAILS_V1", "ADMISSION_DIAGNOSTICS_V1", "SESSION_RECOVERY_DIAGNOSTICS_V1", "VOICE_PLAYBACK_PHASE_V1",
        "CAPACITY_ADMISSION_EVIDENCE_V1", "CONVERSATION_CONFLICT_EVIDENCE_V1", "ACCEPTANCE_SCOPE_EVIDENCE_V1", "VOICE_INSTALL_EVIDENCE_V1",
        "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1", "VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1", "COMPLETION_EVIDENCE_V1", "COMPOSER_IME_EVIDENCE_V1",
        "GITHUB_PREREQUISITE_EVIDENCE_V1", "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1", "ISSUE_RECOVERY_CODES_V1",
        "CAPTURE_BOUNDARY_EVIDENCE_V1", "HANDOFF_RECONCILIATION_EVIDENCE_V1", "CHAT_DELIVERY_EVIDENCE_V1", "CLAIM_FRESHNESS_EVIDENCE_V1",
        "PROFILE_SELECTION_EVIDENCE_V1", "GH_COOLDOWN_V1"
    )

    private fun ready(extended: Boolean): String = buildJsonObject {
        put("service", "gpt-mobile-issue-reporter")
        put("version", 2)
        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
        val features = legacyReadyFeatures + if (extended) listOf(GitHubAcceptancePublicationEvidence.FEATURE) else emptyList()
        put("reportDiagnosticFeatures", JsonArray(features.map(::JsonPrimitive)))
    }.toString()

    private fun publicationReport(): IssueReport = report.copy(
        context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("diagnostic_area" to "ACCEPTANCE", "acceptance_publication_source" to "PARTIAL_FALLBACK"),
            1000L
        )
    )

    @Test fun privacyEligibilityEvidenceAndCodesWaitForTheirOwnCapabilityBeforePostingApprovedBytes() {
        val code = PrivacyFailureEvidence.CODE
        val feature = PrivacyFailureEvidence.FEATURE
        val diagnosticContext = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("diagnostic_area" to "PROVIDER", "privacy_reason_code" to "ACCOUNT_PRIVACY_UNVERIFIED", "privacy_status" to "NOT_VERIFIED"),
            1_000L
        )
        val diagnostic = requireNotNull(diagnosticContext.diagnostics)
        val variants = mutableListOf(
            report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = code),
            report.copy(events = listOf("-1ms E chat $code")),
            report.copy(context = diagnosticContext),
            report.copy(context = diagnosticContext.withDiagnostics(diagnostic.copy(values = emptyMap(), omittedFields = PrivacyFailureEvidence.fields.toList())))
        )
        for (location in listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code")) {
            val area = when (location) {
                "provider_failure_code" -> "PROVIDER"
                "voice_chat_failure_code" -> "VOICE"
                else -> "INCIDENT"
            }
            val context = IssueReportContext.fromFields(if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE", mapOf("diagnostic_area" to area, location to code), 1_000L)
            assertEquals(JsonPrimitive(code), context.diagnostics?.values?.get(location))
            variants += report.copy(context = context)
        }
        for (approved in variants) {
            val payload = approved.approvedPayload()
            val needed = approved.context?.diagnostics?.requiredServiceFeatures().orEmpty() + feature
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(needed.filter { compatible || it != feature }.map(::JsonPrimitive)))
                    }
                    response(request, 200, readiness.toString())
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1741}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1741), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        assertEquals(1022, ready(false).encodeToByteArray().size)
    }

    @Test fun gatewayCapabilityEvidenceCodesAndOmissionsWaitForCompatibleReporterWithoutChangingApprovedBytes() {
        val error = dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException("Capability read failed", decodeReason = "EXPECTED_BOOLEAN", fieldPath = "PRIVATE_PATH")
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("Capability read failed", errorCode = error.code, failureDetails = GatewayCapabilityFailureEvidence.failureDetails(error, false))
        val profile = dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(name = "PRIVATE_NAME", model = "PRIVATE_MODEL", compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        val context = IssueReportContext.fromFields("CHAT_FAILURE", failure.failureDetails.orEmpty() + ProviderFailureReport.fields("run", failure, receipt), 1_000L)
        val diagnostic = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("EXPECTED_BOOLEAN"), diagnostic.values[GatewayCapabilityFailureEvidence.DECODE_REASON])
        val empty = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER"), 1_000L))
        val variants = listOf(
            context,
            context.withDiagnostics(diagnostic.copy(values = diagnostic.values - GatewayCapabilityFailureEvidence.fields, omittedFields = GatewayCapabilityFailureEvidence.fields.toList())),
            context.withDiagnostics(empty.copy(omittedFields = GatewayCapabilityFailureEvidence.fields.toList()))
        ) + GatewayCapabilityFailureEvidence.codes.map { code ->
            IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER", "provider_failure_code" to code), 1_000L)
        }
        for (candidate in variants) {
            val approved = report.copy(context = candidate)
            val payload = approved.approvedPayload()
            val needed = requireNotNull(candidate.diagnostics).requiredServiceFeatures()
            assertTrue(GatewayCapabilityFailureEvidence.FEATURE in needed)
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(needed.filter { compatible || it != GatewayCapabilityFailureEvidence.FEATURE }.map(::JsonPrimitive)))
                    }
                    response(request, 200, readiness.toString())
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1768}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1768), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        assertEquals(1022, ready(false).encodeToByteArray().size)
    }

    @Test fun publicationEvidenceValuesAndOmissionsNegotiateExtendedReadinessBeforeUnchangedPost() {
        assertEquals(1022, ready(false).encodeToByteArray().size)
        assertTrue(ready(true).encodeToByteArray().size in 1025..4096)
        var fixtures = 0
        for (key in GitHubAcceptancePublicationEvidence.fields - GitHubAcceptancePublicationEvidence.observationFields) {
            val value = when (key) {
                "acceptance_publication_source" -> JsonPrimitive("PARTIAL_FALLBACK")
                "acceptance_extraction_observation" -> JsonPrimitive("PLAN_INPUT_LIMIT")
                "acceptance_draft_state" -> JsonPrimitive("PRESENT")
                else -> JsonPrimitive(0)
            }
            for (omitted in listOf(false, true)) {
                val context = requireNotNull(publicationReport().context)
                val base = requireNotNull(context.diagnostics)
                val withoutPublication = base.values - GitHubAcceptancePublicationEvidence.fields
                val diagnostics = if (omitted) {
                    base.copy(values = withoutPublication, omittedFields = listOf(key))
                } else {
                    base.copy(values = withoutPublication + (key to value))
                }
                val approved = report.copy(context = context.withDiagnostics(diagnostics))
                val payload = approved.approvedPayload()
                assertTrue(GitHubAcceptancePublicationEvidence.FEATURE in diagnostics.requiredServiceFeatures())
                var compatible = false
                var dispatched = 0
                val methods = mutableListOf<String>()
                val posted = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                        response(request, 200, ready(compatible))
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":1240}""")
                    }
                }.build()
                val submission = IssueReportSubmission(origin, client)
                val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                assertFalse(blocked.automaticRetry)
                assertEquals(listOf("GET"), methods)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                assertEquals(payload, approved.approvedPayload())
                compatible = true
                assertEquals(IssueReportSubmission.Confirmed(1240), submission.send(report.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "GET", "POST"), methods)
                assertEquals(1, dispatched)
                assertEquals(listOf(payload), posted)
                fixtures++
            }
        }
        assertEquals(16, fixtures)
    }

    @Test fun repeatPersistenceAndAcceptanceCausesNegotiateTheirOwnExtendedFeaturesForValuesAndOmissions() {
        val repeat = IssueReportIncidentCapsule.repeatAdmissionFields.associateWith { key ->
            if (key == "dependency_repeat_scope") JsonPrimitive("TOOL_NAME_AND_ARGUMENTS") else JsonPrimitive(3)
        }
        val persistence = mapOf(
            "tool_event_persistence_operation" to JsonPrimitive("START"),
            "tool_event_persistence_phase" to JsonPrimitive("ALLOCATE_SEQUENCE"),
            "tool_event_persistence_failure" to JsonPrimitive("SEQUENCE_EXHAUSTED"),
            "tool_event_requested_sequence" to JsonPrimitive(Int.MIN_VALUE),
            "tool_event_assigned_sequence" to JsonPrimitive(Int.MAX_VALUE),
            "tool_event_previous_max_sequence" to JsonPrimitive(Int.MAX_VALUE),
            "tool_event_write_attempted" to JsonPrimitive(false)
        )
        val families = listOf(
            Triple("TOOL", IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE, repeat),
            Triple("INCIDENT", IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE, persistence),
            Triple("ACCEPTANCE", GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE, mapOf("acceptance_publication_entry" to JsonPrimitive("STATUS_COLLECTION"), "acceptance_claim_trust_failures" to JsonArray(listOf(JsonPrimitive("TARGET_OUTSIDE_ACCEPTED_SCOPE"))))),
            Triple("INCIDENT", GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE, mapOf("review_comparison_state" to JsonPrimitive("BASE_REF_BLOCKED"), "review_comparison_policy" to JsonPrimitive("EXPLICIT_BASE_REF"))),
            Triple("INCIDENT", IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE, mapOf("sqlite_constraint_kind" to JsonPrimitive("UNKNOWN"))),
            Triple("TOOL", IssueReportIncidentCapsule.URL_READER_AVAILABILITY_FEATURE, mapOf("url_reader_availability" to JsonPrimitive("NEGATIVE_CACHE")))
        )
        val newFeatures = families.map { it.second }.toSet()
        var fixtures = 0
        for ((area, feature, samples) in families) {
            val context = requireNotNull(
                IssueReportContext.fromFields(
                    "CHAT_FAILURE",
                    IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", mapOf("diagnostic_area" to area)),
                    1000L
                )
            )
            val base = requireNotNull(context.diagnostics)
            for ((key, value) in samples) {
                for (omitted in listOf(false, true)) {
                    val diagnostics = if (omitted) base.copy(omittedFields = listOf(key)) else base.copy(values = base.values + (key to value))
                    assertTrue("$key omitted=$omitted", diagnostics.isValid())
                    assertTrue(feature in diagnostics.requiredServiceFeatures())
                    val approved = report.copy(context = context.withDiagnostics(diagnostics))
                    val payload = approved.approvedPayload()
                    var compatible = false
                    var dispatched = 0
                    val methods = mutableListOf<String>()
                    val posted = mutableListOf<String>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        methods += request.method
                        if (request.method == "GET") {
                            assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                            val advertised = (legacyReadyFeatures + GitHubAcceptancePublicationEvidence.FEATURE + newFeatures).filter { compatible || it != feature }
                            val ready = buildJsonObject {
                                put("service", "gpt-mobile-issue-reporter")
                                put("version", 2)
                                put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                                put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                            }.toString()
                            assertTrue(ready.encodeToByteArray().size in 1025..4096)
                            response(request, 200, ready)
                        } else {
                            posted += body(request)
                            response(request, 200, """{"reportId":"${report.reportId}","number":1273}""")
                        }
                    }.build()
                    val submission = IssueReportSubmission(origin, client)
                    val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                    assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                    assertFalse(blocked.automaticRetry)
                    assertEquals(listOf("GET"), methods)
                    assertEquals(0, dispatched)
                    assertTrue(posted.isEmpty())
                    assertEquals(payload, approved.approvedPayload())
                    compatible = true
                    assertEquals(IssueReportSubmission.Confirmed(1273), submission.send(report.reportId, payload) { dispatched++ })
                    assertEquals(listOf("GET", "GET", "POST"), methods)
                    assertEquals(1, dispatched)
                    assertEquals(listOf(payload), posted)
                    fixtures++
                }
            }
        }
        assertEquals(36, fixtures)
        assertEquals(1022, ready(false).encodeToByteArray().size)
    }

    @Test fun policyResponseValuesOmissionsAndUnavailableNamesWaitForCompatibleHostWithoutChangingApproval() {
        val feature = IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE
        val samples = mapOf(
            "status_policy_protection_404_kind" to JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED"),
            "status_policy_rules_404_kind" to JsonPrimitive("OTHER_404"),
            "status_policy_protection_permission_header_names" to JsonArray(emptyList()),
            "status_policy_rules_permission_header_names" to JsonArray(emptyList())
        )
        val context = requireNotNull(
            IssueReportContext.fromFields("CHAT_FAILURE", IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "GITHUB_TASK_INCOMPLETE", mapOf("diagnostic_area" to "ACCEPTANCE")), 1000L)
        )
        val base = requireNotNull(context.diagnostics)
        for ((key, value) in samples) {
            for (diagnostic in listOf(
                base.copy(values = base.values + (key to value)),
                base.copy(omittedFields = listOf(key)),
                base.copy(unavailableFields = base.unavailableFields + key)
            )) {
                assertTrue(diagnostic.isValid())
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                val approved = report.copy(context = context.withDiagnostics(diagnostic))
                val payload = approved.approvedPayload()
                var compatible = false
                var dispatched = 0
                val methods = mutableListOf<String>()
                val posted = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                        val advertised = legacyReadyFeatures + listOf(
                            GitHubAcceptancePublicationEvidence.FEATURE,
                            IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE,
                            IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE,
                            GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE,
                            IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE
                        ) + if (compatible) listOf(feature) else emptyList()
                        val readiness = buildJsonObject {
                            put("service", "gpt-mobile-issue-reporter")
                            put("version", 2)
                            put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                            put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                        }.toString()
                        assertTrue(readiness.encodeToByteArray().size in 1025..4096)
                        response(request, 200, readiness)
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":1293}""")
                    }
                }.build()
                val submission = IssueReportSubmission(origin, client)
                val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                assertFalse(blocked.automaticRetry)
                assertEquals(listOf("GET"), methods)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                assertEquals(payload, approved.approvedPayload())
                compatible = true
                assertEquals(IssueReportSubmission.Confirmed(1293), submission.send(report.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "GET", "POST"), methods)
                assertEquals(1, dispatched)
                assertEquals(listOf(payload), posted)
            }
        }
        assertEquals(1022, ready(false).encodeToByteArray().size)
    }

    @Test fun repeatHistoryAndAbsenceMarkersWaitForCompatibleHostAndRejectPartialValueGroups() {
        val feature = IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE
        val operands = mapOf(
            "dependency_repeat_scope" to JsonPrimitive("TOOL_NAME_AND_ARGUMENTS"),
            "dependency_repeat_prior_admissions" to JsonPrimitive(1),
            "dependency_repeat_batch_admissions" to JsonPrimitive(0),
            "dependency_repeat_used" to JsonPrimitive(1),
            "dependency_repeat_limit" to JsonPrimitive(1)
        )
        val history = mapOf(
            "dependency_repeat_prior_call_id_hashes" to JsonArray(listOf(JsonPrimitive("a".repeat(64)))),
            "dependency_repeat_prior_outcomes" to JsonArray(listOf(JsonPrimitive("UNKNOWN_OUTCOME"))),
            "dependency_repeat_prior_coverage" to JsonPrimitive("COMPLETE"),
            "dependency_repeat_batch_call_id_hashes" to JsonArray(emptyList()),
            "dependency_repeat_batch_coverage" to JsonPrimitive("COMPLETE")
        )
        val context = requireNotNull(
            IssueReportContext.fromFields("TOOL_RESULT", IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "TOOL_FAILURE", mapOf("diagnostic_area" to "TOOL") + operands), 1000L)
        )
        val base = requireNotNull(context.diagnostics)
        val forms = listOf(base.copy(values = base.values + history)) + IssueReportIncidentCapsule.repeatHistoryFields.flatMap { key ->
            listOf(base.copy(omittedFields = listOf(key)), base.copy(unavailableFields = base.unavailableFields + key))
        }
        for (diagnostic in forms) {
            assertTrue(diagnostic.isValid())
            assertTrue(feature in diagnostic.requiredServiceFeatures())
            val approved = report.copy(context = context.withDiagnostics(diagnostic))
            val payload = approved.approvedPayload()
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val advertised = legacyReadyFeatures + listOf(
                        GitHubAcceptancePublicationEvidence.FEATURE,
                        IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE,
                        IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE,
                        GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE,
                        IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE
                    ) + if (compatible) listOf(feature) else emptyList()
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                    }.toString()
                    assertTrue(readiness.encodeToByteArray().size in 1025..4096)
                    response(request, 200, readiness)
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1277}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1277), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Invalid restored payload must not dispatch")
        }.build()
        val malformed = base.copy(values = base.values + (history - "dependency_repeat_prior_outcomes"))
        val payload = IssueReport.json.encodeToString(IssueReport.serializer(), report.copy(context = context.copy(diagnostics = malformed)))
        assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        val topLevelOnly = context.copy(callIdHash = "a".repeat(64), unavailableFields = context.unavailableFields - "callIdHash", diagnostics = base.copy(values = base.values + history))
        val selfLinkedPayload = IssueReport.json.encodeToString(IssueReport.serializer(), report.copy(context = topLevelOnly))
        assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, selfLinkedPayload) as IssueReportSubmission.Failure).code)
        assertEquals(0, requests)
    }

    @Test fun ciRuleValuesAndOmissionsWaitForCompatibleHostWithoutChangingApprovedPayload() {
        val key = "semantic_ci_rules"
        val feature = IssueReportIncidentCapsule.GITHUB_CI_SEMANTIC_FEATURE
        val context = requireNotNull(
            IssueReportContext.fromFields("TOOL_RESULT", IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "INVALID_ARGUMENTS", mapOf("diagnostic_area" to "TOOL")), 1000L)
        )
        val base = requireNotNull(context.diagnostics)
        val observed = JsonArray(listOf(JsonPrimitive("LOG_SPAN"), JsonPrimitive("WORKFLOW_SELECTOR")))
        for (diagnostic in listOf(base.copy(values = base.values + (key to observed)), base.copy(omittedFields = listOf(key)))) {
            assertTrue(diagnostic.isValid())
            val approved = report.copy(context = context.withDiagnostics(diagnostic))
            val payload = approved.approvedPayload()
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val advertised = legacyReadyFeatures + listOf(
                        GitHubAcceptancePublicationEvidence.FEATURE,
                        GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE,
                        IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE,
                        IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE
                    ) + if (compatible) listOf(feature) else emptyList()
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                    }.toString()
                    assertTrue(readiness.encodeToByteArray().size in 1025..4096)
                    response(request, 200, readiness)
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1297}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1297), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Unavailable CI rules must not dispatch")
        }.build()
        val invalid = base.copy(unavailableFields = base.unavailableFields + key)
        val payload = IssueReport.json.encodeToString(IssueReport.serializer(), report.copy(context = context.copy(diagnostics = invalid)))
        assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        assertEquals(0, requests)
    }

    @Test fun paginationQuotaAndStreamEvidenceSelectExtendedReporterReadiness() {
        val samples = listOf(
            Triple("TOOL", "remote_pagination_page", JsonPrimitive(6)),
            Triple("TOOL", "pr_context_revision_changed", JsonPrimitive(true)),
            Triple("PROVIDER", "provider_limit_source", JsonPrimitive("upstream_provider_shared_pool")),
            Triple("PROVIDER", "tool_protocol_frame_kind", JsonPrimitive("UNFINISHED_STREAM"))
        )
        for ((area, field, sample) in samples) {
            val context = requireNotNull(
                IssueReportContext.fromFields("CHAT_FAILURE", IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", mapOf("diagnostic_area" to area)), 1000L)
            )
            val base = requireNotNull(context.diagnostics)
            for (diagnostic in listOf(base.copy(values = base.values + (field to sample)), base.copy(omittedFields = listOf(field)))) {
                val payload = report.copy(context = context.withDiagnostics(diagnostic)).approvedPayload()
                val calls = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    calls += request.method
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    response(request, 200, ready(false))
                }.build()
                val failure = IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", failure.code)
                assertEquals(listOf("GET"), calls)
            }
        }
    }

    @Test fun inventoryPaginationValuesAndOmissionsWaitForCompatibleHostWithoutChangingApproval() {
        val feature = IssueReportIncidentCapsule.TOOL_INVENTORY_PAGINATION_FEATURE
        val context = requireNotNull(
            IssueReportContext.fromFields("TOOL_RESULT", IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "TOOL_FAILURE", mapOf("diagnostic_area" to "TOOL")), 1000L)
        )
        val base = requireNotNull(context.diagnostics)
        val values = listOf(
            "inventory_pagination_reason" to JsonPrimitive("MISSING_VERSION"),
            "inventory_pagination_reason" to JsonPrimitive("VERSION_MISMATCH"),
            "inventory_restart_offered" to JsonPrimitive(false),
            "inventory_restart_offered" to JsonPrimitive(true)
        )
        val forms = values.map { base.copy(values = base.values + it) } +
            IssueReportIncidentCapsule.inventoryPaginationFields.map { base.copy(omittedFields = listOf(it)) }
        for (diagnostic in forms) {
            assertTrue(diagnostic.isValid())
            val approved = report.copy(context = context.withDiagnostics(diagnostic))
            val payload = approved.approvedPayload()
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val advertised = legacyReadyFeatures + listOf(
                        GitHubAcceptancePublicationEvidence.FEATURE,
                        GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE,
                        IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE,
                        IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_CI_SEMANTIC_FEATURE
                    ) + if (compatible) listOf(feature) else emptyList()
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                    }.toString()
                    assertTrue(readiness.encodeToByteArray().size in 1025..4096)
                    response(request, 200, readiness)
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1301}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1301), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Unavailable inventory observations must not dispatch")
        }.build()
        for (key in IssueReportIncidentCapsule.inventoryPaginationFields) {
            val invalid = base.copy(unavailableFields = base.unavailableFields + key)
            val payload = IssueReport.json.encodeToString(IssueReport.serializer(), report.copy(context = context.copy(diagnostics = invalid)))
            assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        }
        assertEquals(0, requests)
    }

    @Test fun searchValidationNamesAndOmissionsWaitForCompatibleHostWithoutChangingApproval() {
        val key = "search_validation_field_names"
        val feature = IssueReportIncidentCapsule.GITHUB_SEARCH_VALIDATION_FEATURE
        val context = requireNotNull(
            IssueReportContext.fromFields("TOOL_RESULT", IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "INVALID_ARGUMENTS", mapOf("diagnostic_area" to "TOOL")), 1000L)
        )
        val base = requireNotNull(context.diagnostics)
        val observed = JsonArray(listOf("batch_size", "context_lines", "max_new_blobs", "fuzzy_threshold").map(::JsonPrimitive))
        for (diagnostic in listOf(base.copy(values = base.values + (key to observed)), base.copy(omittedFields = listOf(key)))) {
            assertTrue(diagnostic.isValid())
            val approved = report.copy(context = context.withDiagnostics(diagnostic))
            val payload = approved.approvedPayload()
            var compatible = false
            var dispatched = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val advertised = legacyReadyFeatures + listOf(
                        GitHubAcceptancePublicationEvidence.FEATURE,
                        GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE,
                        IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE,
                        IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE,
                        IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE,
                        IssueReportIncidentCapsule.GITHUB_CI_SEMANTIC_FEATURE,
                        IssueReportIncidentCapsule.TOOL_INVENTORY_PAGINATION_FEATURE
                    ) + if (compatible) listOf(feature) else emptyList()
                    val readiness = buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(advertised.map(::JsonPrimitive)))
                    }.toString()
                    assertTrue(readiness.encodeToByteArray().size in 1025..4096)
                    response(request, 200, readiness)
                } else {
                    posted += body(request)
                    response(request, 200, """{"reportId":"${report.reportId}","number":1302}""")
                }
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatched)
            assertTrue(posted.isEmpty())
            assertEquals(payload, approved.approvedPayload())
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(1302), submission.send(report.reportId, payload) { dispatched++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            error("Unavailable search validation names must not dispatch")
        }.build()
        val invalid = base.copy(unavailableFields = base.unavailableFields + key)
        val payload = IssueReport.json.encodeToString(IssueReport.serializer(), report.copy(context = context.copy(diagnostics = invalid)))
        assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        assertEquals(0, requests)
    }

    @Test fun extendedReadinessIsBoundedAtFourKiBAndOversizeNeverPosts() {
        val payload = publicationReport().approvedPayload()
        for (size in listOf(4096, 4097)) {
            val ready = ready(true).let { it + " ".repeat(size - it.encodeToByteArray().size) }
            assertEquals(size, ready.encodeToByteArray().size)
            var dispatched = 0
            var posts = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    response(request, 200, ready)
                } else {
                    posts++
                    response(request, 200, """{"reportId":"${report.reportId}","number":1240}""")
                }
            }.build()
            val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatched++ }
            if (size == 4096) {
                assertEquals(IssueReportSubmission.Confirmed(1240), outcome)
                assertEquals(1, dispatched)
                assertEquals(1, posts)
            } else {
                assertEquals("REPORT_SERVICE_UNAVAILABLE", (outcome as IssueReportSubmission.Failure).code)
                assertEquals(0, dispatched)
                assertEquals(0, posts)
            }
        }
    }

    @Test fun legacyReportsRetainPlainReadinessAndTheOneKiBBound() {
        val approved = report.copy(context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE"), 1000L))
        val payload = approved.approvedPayload()
        assertFalse(GitHubAcceptancePublicationEvidence.FEATURE in requireNotNull(approved.context?.diagnostics).requiredServiceFeatures())
        for (size in listOf(1024, 1025)) {
            val ready = ready(false).let { it + " ".repeat(size - it.encodeToByteArray().size) }
            var posts = 0
            var dispatched = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready", request.url.toString())
                    response(request, 200, ready)
                } else {
                    posts++
                    response(request, 200, """{"reportId":"${report.reportId}","number":1240}""")
                }
            }.build()
            val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatched++ }
            if (size == 1024) {
                assertEquals(IssueReportSubmission.Confirmed(1240), outcome)
                assertEquals(1, posts)
                assertEquals(1, dispatched)
            } else {
                assertEquals("REPORT_SERVICE_UNAVAILABLE", (outcome as IssueReportSubmission.Failure).code)
                assertEquals(0, posts)
                assertEquals(0, dispatched)
            }
        }
    }

    @Test fun extendedReadinessDoesNotEnlargeTheSubmissionReceiptBound() {
        val payload = publicationReport().approvedPayload()
        for (size in listOf(1024, 1025)) {
            val receipt = """{"reportId":"${report.reportId}","number":1240}""".let { it + " ".repeat(size - it.encodeToByteArray().size) }
            var dispatched = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "GET") {
                    response(request, 200, ready(true))
                } else {
                    assertEquals(payload, body(request))
                    response(request, 200, receipt)
                }
            }.build()
            val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatched++ }
            assertEquals(1, dispatched)
            if (size == 1024) {
                assertEquals(IssueReportSubmission.Confirmed(1240), outcome)
            } else {
                assertEquals("REPORT_RECEIPT_UNAVAILABLE", (outcome as IssueReportSubmission.Failure).code)
            }
        }
    }

    @Test fun newEvidenceValuesAndOmittedNamesWaitForCompatibleReporterWithoutChangingApproval() {
        var fixtures = 0
        for ((feature, family) in listOf("HANDOFF_RECONCILIATION_EVIDENCE_V1" to IssueReportIncidentCapsule.handoffReconciliationFields, "CHAT_DELIVERY_EVIDENCE_V1" to IssueReportIncidentCapsule.chatDeliveryFields, "CLAIM_FRESHNESS_EVIDENCE_V1" to IssueReportIncidentCapsule.claimFreshnessFields)) {
            val area = if (feature == "CLAIM_FRESHNESS_EVIDENCE_V1") "ACCEPTANCE" else "INCIDENT"
            val fields = IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "RECONCILIATION_REQUIRED", mapOf("diagnostic_area" to area))
            val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
            val base = requireNotNull(context.diagnostics)
            for (key in family) {
                val value = when {
                    key == "acceptance_claim_freshness_predicates" -> JsonArray(emptyList())
                    key.endsWith("_hash") -> JsonPrimitive("a".repeat(64))
                    key == "handoff_checkpoint_state" -> JsonPrimitive("TOOL_HANDOFF")
                    key == "handoff_durable_lookup" -> JsonPrimitive("RETURNED_EMPTY")
                    key == "chat_delivery_terminal" -> JsonPrimitive("DONE")
                    key == "chat_delivery_answer_state" -> JsonPrimitive("EMPTY_CONTENT")
                    key == "chat_delivery_counts_capped" -> JsonPrimitive(false)
                    else -> JsonPrimitive(0)
                }
                for (omitted in listOf(false, true)) {
                    val diagnostics = if (omitted) base.copy(omittedFields = listOf(key)) else base.copy(values = base.values + (key to value))
                    val approved = report.copy(context = context.withDiagnostics(diagnostics))
                    val payload = approved.approvedPayload()
                    val requiredFeatures = diagnostics.requiredServiceFeatures()
                    assertTrue("$key omitted=$omitted", feature in requiredFeatures)
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
                    val blocked = submission.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                    assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
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
                    fixtures++
                }
            }
        }
        assertEquals(42, fixtures)
    }

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
            "CAPTURE_BOUNDARY_EVIDENCE_V1" to mapOf("incident_capture_site" to "CHAT_REPOSITORY_ERROR_VALUE", "incident_phase_provenance" to "CAPTURE_BOUNDARY_ONLY"),
            "CHAT_DELIVERY_EVIDENCE_V1" to mapOf("incident_capture_site" to "CHAT_FLOW_EMPTY_ANSWER"),
            "CLAIM_FRESHNESS_EVIDENCE_V1" to mapOf("acceptance_claim_freshness_predicates" to listOf("MUTABLE_AGE_EXCEEDED", "MUTABLE_EPOCH_MISMATCH"))
        )) {
            val area = when (feature) {
                "ACCEPTANCE_SCOPE_EVIDENCE_V1", "GITHUB_PREREQUISITE_EVIDENCE_V1", "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1" -> "ACCEPTANCE"
                "CLAIM_FRESHNESS_EVIDENCE_V1" -> "ACCEPTANCE"
                "VOICE_INSTALL_EVIDENCE_V1" -> "VOICE"
                "CHAT_DELIVERY_EVIDENCE_V1" -> "INCIDENT"
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

    @Test fun githubCooldownValuesAndOmittedFieldsWaitWithoutChangingApprovedBytes() {
        val feature = "GH_COOLDOWN_V1"
        val samples = mapOf(
            "github_cooldown_resource" to JsonPrimitive("CORE"),
            "github_cooldown_basis" to JsonPrimitive("RESOURCE_RESET"),
            "github_cooldown_delay_ms" to JsonPrimitive(Long.MAX_VALUE),
            "github_cooldown_deadline_ms" to JsonPrimitive(Long.MAX_VALUE),
            "github_cooldown_observed_at_ms" to JsonPrimitive(100_000L),
            "github_cooldown_remaining_requests" to JsonPrimitive(0L)
        )
        assertEquals(IssueReportDiagnostics.githubCooldownFields, samples.keys)
        for (area in listOf("PROVIDER", "TOOL")) {
            val empty = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to area), 1_000L)
            val base = requireNotNull(empty.diagnostics)
            for ((key, value) in samples) {
                for (capture in listOf(base.copy(values = base.values + (key to value)), base.copy(omittedFields = listOf(key)))) {
                    assertTrue(capture.isValid())
                    val enriched = report.copy(context = empty.withDiagnostics(capture))
                    val payload = enriched.approvedPayload()
                    val features = capture.requiredServiceFeatures()
                    assertTrue(feature in features)
                    var supported = false
                    var dispatched = 0
                    val methods = mutableListOf<String>()
                    val posted = mutableListOf<String>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        methods += request.method
                        if (request.method == "GET") {
                            val advertised = JsonArray(features.filter { supported || it != feature }.map(::JsonPrimitive))
                            response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$advertised}""")
                        } else {
                            posted += body(request)
                            response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                        }
                    }.build()
                    val service = IssueReportSubmission(origin, client)
                    val blocked = service.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                    assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                    assertFalse(blocked.automaticRetry)
                    assertEquals(listOf("GET"), methods)
                    assertEquals(0, dispatched)
                    assertTrue(posted.isEmpty())
                    assertEquals(payload, enriched.approvedPayload())
                    supported = true
                    assertEquals(IssueReportSubmission.Confirmed(941), service.send(report.reportId, payload) { dispatched++ })
                    assertEquals(listOf("GET", "GET", "POST"), methods)
                    assertEquals(1, dispatched)
                    assertEquals(listOf(payload), posted)
                }
            }
        }
    }

    @Test fun profileSelectionValuesAndOmittedFieldsWaitForTheirOwnServiceCapability() {
        val feature = "PROFILE_SELECTION_EVIDENCE_V1"
        val empty = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER"), 1_000L)
        val base = requireNotNull(empty.diagnostics)
        for (key in IssueReportDiagnostics.profileSelectionFields) {
            val value = if (key.endsWith("_hash")) JsonPrimitive("a".repeat(64)) else JsonPrimitive(false)
            for (capture in listOf(base.copy(values = base.values + (key to value)), base.copy(omittedFields = listOf(key)))) {
                assertTrue(capture.isValid())
                val enriched = report.copy(context = empty.withDiagnostics(capture))
                val payload = enriched.approvedPayload()
                val features = capture.requiredServiceFeatures()
                assertTrue(feature in features)
                var supported = false
                var dispatched = 0
                val methods = mutableListOf<String>()
                val posted = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        val advertised = JsonArray(features.filter { supported || it != feature }.map(::JsonPrimitive))
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$advertised}""")
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                    }
                }.build()
                val service = IssueReportSubmission(origin, client)
                val blocked = service.send(report.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
                assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
                assertFalse(blocked.automaticRetry)
                assertEquals(listOf("GET"), methods)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                assertEquals(payload, enriched.approvedPayload())
                supported = true
                assertEquals(IssueReportSubmission.Confirmed(941), service.send(report.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "GET", "POST"), methods)
                assertEquals(1, dispatched)
                assertEquals(listOf(payload), posted)
            }
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
        val locations = listOf("errorCode", "originalFailureCode", "events", "provider_failure_code", "causal_failure_code", "voice_chat_failure_code", "tool_error_code")
        var retainedFixtures = 0
        val legacyRateCodes = setOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")
        for (code in recoveryCodes + listOf("TOOL_PROTOCOL_INVALID_FRAME", "GITHUB_PROFILE_LOCKED", "GITHUB_COOLDOWN_ACTIVE") + legacyRateCodes) {
            val feature = when (code) {
                "GITHUB_COOLDOWN_ACTIVE", in legacyRateCodes -> "GH_COOLDOWN_V1"
                "GITHUB_PROFILE_LOCKED" -> "PROFILE_SELECTION_EVIDENCE_V1"
                "TOOL_PROTOCOL_INVALID_FRAME" -> "COMPLETION_EVIDENCE_V1"
                else -> "ISSUE_RECOVERY_CODES_V1"
            }
            for (location in locations) {
                if (code in legacyRateCodes && location != "provider_failure_code") continue
                if (location == "tool_error_code" && code != "GITHUB_COOLDOWN_ACTIVE") continue
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
                            "tool_error_code" -> "TOOL"
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
        assertEquals(76, retainedFixtures)
    }

    @Test fun legacyGitHubRateCodesKeepTheirExistingHostedCompatibility() {
        for (code in listOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            for (location in listOf("errorCode", "originalFailureCode", "events", "causal_failure_code", "voice_chat_failure_code", "tool_error_code")) {
                val context = when (location) {
                    "errorCode", "events" -> null
                    "originalFailureCode" -> IssueReportContext.fromFields(
                        "CHAT_FAILURE",
                        mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "original_interruption" to mapOf("code" to code)),
                        1_000L
                    ).also { assertEquals(code, it.originalFailureCode) }
                    else -> IssueReportContext.fromFields(
                        if (location == "voice_chat_failure_code") "VOICE_STATE" else "CHAT_FAILURE",
                        mapOf(
                            "diagnostic_area" to when (location) {
                                "voice_chat_failure_code" -> "VOICE"
                                "tool_error_code" -> "TOOL"
                                else -> "INCIDENT"
                            },
                            location to code
                        ),
                        1_000L
                    ).also { assertEquals(JsonPrimitive(code), it.diagnostics?.values?.get(location)) }
                }
                val enriched = when (location) {
                    "errorCode" -> report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = code)
                    "events" -> report.copy(events = listOf("-1ms E tools $code"))
                    else -> report.copy(context = context)
                }
                val features = context?.diagnostics?.requiredServiceFeatures().orEmpty()
                assertFalse("GH_COOLDOWN_V1" in features)
                val payload = enriched.approvedPayload()
                val methods = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":${JsonArray(features.map(::JsonPrimitive))}}""")
                    } else {
                        assertEquals(payload, body(request))
                        response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                    }
                }.build()
                assertEquals("$code at $location", IssueReportSubmission.Confirmed(941), IssueReportSubmission(origin, client).send(report.reportId, payload))
                assertEquals(if (context == null) listOf("POST") else listOf("GET", "POST"), methods)
                assertEquals(payload, enriched.approvedPayload())
            }
        }
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

    @Test fun composerAndToolExtensionsNegotiateProtocolTwoForValuesAndEveryOmittedName() {
        val composer = buildMap<String, Any?> {
            put("diagnostic_area", "APP")
            put("composer_generation", 1)
            put("composer_events_dropped", 0)
            put("composer_enabled", true)
            put("composer_window_focused", false)
            put("composer_lifecycle", "RESUMED")
            put("composer_input_mode", "TOUCH")
            for (slot in 1..8) {
                put("composer_event_$slot", "IME_HIDDEN")
                put("composer_event_${slot}_age_ms", 0)
            }
        }
        val inputs = listOf(composer, mapOf("diagnostic_area" to "TOOL", "tool_budget_bytes_available" to 0), mapOf("diagnostic_area" to "TOOL", "graph_index_attempt_hash" to "a".repeat(64)))
        for (fields in inputs) {
            val context = IssueReportContext.fromFields("MANUAL_REPORT", fields, 1000)
            val observed = requireNotNull(context.diagnostics)
            val absent = requireNotNull(IssueReportContext.fromFields("MANUAL_REPORT", mapOf("diagnostic_area" to fields.getValue("diagnostic_area")), 1000).diagnostics)
            for (capture in listOf(observed) + observed.values.keys.map { absent.copy(omittedFields = listOf(it)) }) {
                assertTrue(capture.isValid())
                assertEquals(2, capture.requiredReporterVersion())
                val approved = report.copy(context = context.copy(diagnostics = capture))
                val payload = approved.approvedPayload()
                val features = JsonArray(capture.requiredServiceFeatures().map(::JsonPrimitive))
                for (version in listOf(null, 1, 2)) {
                    var dispatches = 0
                    val posted = mutableListOf<String>()
                    val methods = mutableListOf<String>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        methods += request.method
                        if (request.method == "GET") {
                            val protocol = version?.let { "\"version\":$it," }.orEmpty()
                            response(request, 200, """{"service":"gpt-mobile-issue-reporter",$protocol"reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                        } else {
                            posted += body(request)
                            response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                        }
                    }.build()
                    val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatches++ }
                    if (version == 2) {
                        assertEquals(IssueReportSubmission.Confirmed(941), outcome)
                        assertEquals(1, dispatches)
                        assertEquals(listOf("GET", "POST"), methods)
                        assertEquals(listOf(payload), posted)
                    } else {
                        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (outcome as IssueReportSubmission.Failure).code)
                        assertFalse(outcome.automaticRetry)
                        assertEquals(0, dispatches)
                        assertEquals(listOf("GET"), methods)
                        assertTrue(posted.isEmpty())
                    }
                    assertEquals(payload, approved.approvedPayload())
                }
            }
        }
    }

    @Test fun malformedComposerPairFailsBeforeReadinessAndLegacyFourFieldsKeepOriginalBytes() {
        val context = IssueReportContext.fromFields("MANUAL_REPORT", mapOf("diagnostic_area" to "APP", "composer_focus_transitions" to 12, "composer_ime_transitions" to 12, "composer_focused" to false, "composer_ime_visible" to false), 1000)
        val observed = requireNotNull(context.diagnostics)
        assertEquals(0, observed.requiredReporterVersion())
        val payload = report.copy(context = context).approvedPayload()
        val features = JsonArray(observed.requiredServiceFeatures().map(::JsonPrimitive))
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
            } else {
                assertEquals(payload, body(request))
                response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
            }
        }.build()
        assertEquals(IssueReportSubmission.Confirmed(941), IssueReportSubmission(origin, client).send(report.reportId, payload))
        assertEquals(listOf("GET", "POST"), methods)
        assertFalse(payload.contains("composer_generation"))
        val broken = payload.replace("\"composer_focused\":false", "\"composer_focused\":false,\"composer_generation\":1,\"composer_events_dropped\":0,\"composer_event_1\":\"ATTACHED\"")
        assertTrue(broken != payload)
        methods.clear()
        val outcome = IssueReportSubmission(origin, client).send(report.reportId, broken) { error("Invalid pair cannot dispatch") }
        assertEquals("REPORT_PAYLOAD_INVALID", (outcome as IssueReportSubmission.Failure).code)
        assertTrue(methods.isEmpty())
    }

    @Test fun reasoningProbeValuesAndAbsenceDeclarationsRequireProtocolTwoBeforeDispatch() {
        val base = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER"), 1000)
        val diagnostics = requireNotNull(base.diagnostics)
        val observations = mapOf("reasoning_probe_outcome" to JsonPrimitive("IDENTICAL_PROMPTS"), "reasoning_probe_attempts" to JsonPrimitive(2), "reasoning_probe_age_ms" to JsonPrimitive(8000), "reasoning_metadata_age_ms" to JsonPrimitive(8))
        for ((field, value) in observations) {
            for (capture in listOf(
                diagnostics.copy(values = mapOf(field to value)),
                diagnostics.copy(omittedFields = listOf(field)),
                diagnostics.copy(unavailableFields = diagnostics.unavailableFields + field)
            )) {
                assertTrue(capture.isValid())
                val approved = report.copy(context = base.copy(diagnostics = capture))
                val payload = approved.approvedPayload()
                val features = JsonArray(capture.requiredServiceFeatures().map(::JsonPrimitive))
                for (version in listOf(null, 1, 2)) {
                    var dispatches = 0
                    val posted = mutableListOf<String>()
                    val methods = mutableListOf<String>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        methods += request.method
                        if (request.method == "GET") {
                            val protocol = version?.let { "\"version\":$it," }.orEmpty()
                            response(request, 200, """{"service":"gpt-mobile-issue-reporter",$protocol"reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
                        } else {
                            posted += body(request)
                            response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                        }
                    }.build()
                    val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatches++ }
                    if (version == 2) {
                        assertEquals(IssueReportSubmission.Confirmed(941), outcome)
                        assertEquals(1, dispatches)
                        assertEquals(listOf("GET", "POST"), methods)
                        assertEquals(listOf(payload), posted)
                    } else {
                        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (outcome as IssueReportSubmission.Failure).code)
                        assertFalse(outcome.automaticRetry)
                        assertEquals(0, dispatches)
                        assertEquals(listOf("GET"), methods)
                        assertTrue(posted.isEmpty())
                    }
                    assertEquals(payload, approved.approvedPayload())
                }
            }
        }
    }

    @Test fun historicalReasoningAgePayloadKeepsExactApprovedBytesWithLegacyReporter() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER", "reasoning_capability_age_ms" to 8), 1000)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(0, diagnostics.requiredReporterVersion())
        val payload = report.copy(context = context).approvedPayload()
        val features = JsonArray(diagnostics.requiredServiceFeatures().map(::JsonPrimitive))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
            } else {
                assertEquals(payload, body(request))
                response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
            }
        }.build()
        assertEquals(IssueReportSubmission.Confirmed(941), IssueReportSubmission(origin, client).send(report.reportId, payload))
        assertTrue(payload.contains("reasoning_capability_age_ms"))
        assertFalse(payload.contains("reasoning_metadata_age_ms"))
    }

    @Test fun preciseVoiceReasonsRequireNumericReporterProtocolBeforeDispatchingExactApprovedBytes() {
        for (reason in listOf("PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE")) {
            val context = IssueReportContext.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "VOICE", "voice_playback_failure" to reason), 1_000L)
            val diagnostics = requireNotNull(context.diagnostics)
            val approved = report.copy(context = context)
            val payload = approved.approvedPayload()
            assertEquals(2, diagnostics.requiredReporterVersion())
            val features = JsonArray(diagnostics.requiredServiceFeatures().map(::JsonPrimitive))
            val versions = listOf(null, "null", "true", "false", "\"2\"", "2.0", "[]", "{}", "-1", "0", "1", "2", "3").map { it to true } + ("2" to false)
            for ((version, hasFeatures) in versions) {
                var dispatched = 0
                val posted = mutableListOf<String>()
                val methods = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    methods += request.method
                    if (request.method == "GET") {
                        val protocol = version?.let { "\"version\":$it," }.orEmpty()
                        response(request, 200, """{"service":"gpt-mobile-issue-reporter",$protocol"reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":${if (hasFeatures) features else JsonArray(emptyList())}}""")
                    } else {
                        posted += body(request)
                        response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
                    }
                }.build()
                val outcome = IssueReportSubmission(origin, client).send(report.reportId, payload) { dispatched++ }
                if (hasFeatures && version in listOf("2", "3")) {
                    assertEquals(IssueReportSubmission.Confirmed(941), outcome)
                    assertEquals(1, dispatched)
                    assertEquals(listOf("GET", "POST"), methods)
                    assertEquals(listOf(payload), posted)
                } else {
                    assertTrue("$reason version=$version", outcome is IssueReportSubmission.Failure)
                    assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (outcome as IssueReportSubmission.Failure).code)
                    assertFalse(outcome.automaticRetry)
                    assertEquals(0, dispatched)
                    assertEquals(listOf("GET"), methods)
                    assertTrue(posted.isEmpty())
                }
                assertEquals(payload, approved.approvedPayload())
            }
        }
    }

    @Test fun legacyVoiceReasonsKeepTheirExistingCapabilityContractWithoutAProtocolVersion() {
        val context = IssueReportContext.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "VOICE", "voice_playback_failure" to "SYNTHESIS_FAILED"), 1_000L)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(0, diagnostics.requiredReporterVersion())
        val payload = report.copy(context = context).approvedPayload()
        val features = JsonArray(diagnostics.requiredServiceFeatures().map(::JsonPrimitive))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                response(request, 200, """{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2,3,4,5,6,7],"reportDiagnosticFeatures":$features}""")
            } else {
                assertEquals(payload, body(request))
                response(request, 200, """{"reportId":"${report.reportId}","number":941}""")
            }
        }.build()
        assertEquals(IssueReportSubmission.Confirmed(941), IssueReportSubmission(origin, client).send(report.reportId, payload))
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
