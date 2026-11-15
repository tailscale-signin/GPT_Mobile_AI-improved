package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceLedger
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceReportDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidencePolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPolicyResponseCompositionTest {
    private val time = 1_800_000_000_000L
    private val head = "a".repeat(40)
    private val feature = "GITHUB_POLICY_RESPONSE_EVIDENCE_V1"
    private val samples = linkedMapOf(
        "status_policy_protection_404_kind" to JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED"),
        "status_policy_rules_404_kind" to JsonPrimitive("NOT_FOUND"),
        "status_policy_protection_permission_header_names" to JsonArray(emptyList()),
        "status_policy_rules_permission_header_names" to JsonArray(emptyList())
    )

    @Test fun actualPolicyHttpCarrierReachesSchemaThreeApprovedPayloadWithMeasuredEmptyHeaders() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Get, request.method)
                when (request.url.encodedPath) {
                    "/repos/private-owner/private-repo/branches/private-main" -> respond("""{"name":"private-main","protected":true,"commit":{"sha":"$head"}}""")
                    "/repos/private-owner/private-repo/branches/private-main/protection" -> respond("""{"message":"Branch not protected","private":"private-body-sentinel"}""", HttpStatusCode.NotFound)
                    "/repos/private-owner/private-repo/rules/branches/private-main" -> respond("""{"message":"Not Found","private":"private-rules-sentinel"}""", HttpStatusCode.NotFound)
                    else -> error("Unexpected request")
                }
            }
        )
        try {
            val policy = GitHubWorkspaceClient("private-token-sentinel", http, persistentState = false)
                .execute("branch_context", json("""{"owner":"private-owner","repo":"private-repo","branch":"private-main"}""")) as JsonObject
            assertEquals(3, requests)
            assertEquals(JsonPrimitive(false), policy.getValue("policy").jsonObject["required_checks_complete"])
            val records = linkedMapOf(
                "status" to receipt("status", json("""{"repository":{"full_name":"private-owner/private-repo"},"branch":"private-main","commit_sha":"$head","latest_workflow":{"head_sha":"$head","head_branch":"private-main","status":"completed"}}""")),
                "commits" to receipt("commits", json("""{"commit_sha":"$head","items":[]}""")),
                "checks" to receipt("checks", json("""{"commit_sha":"$head","checks":0,"statuses":0,"page":1,"has_more":false}""")),
                "prs" to receipt("list", json("""{"items":[],"page":1,"has_more":false}"""), tool = "github_pr"),
                "failures" to receipt("runs", json("""{"workflow_runs":[],"returned":0}"""), tool = "github_ci", additionalArguments = json("""{"status":"failure"}""")),
                "policy" to receipt("branch", policy)
            )
            val health = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
            val verdict = requireNotNull(GitHubEvidencePolicy.status(records, time, 0, health, acceptedRepository = "private-owner/private-repo")).second
            assertEquals(JsonPrimitive(false), verdict["completion_verified"])
            // The retained report path attaches its actual requirement ledger before projection.
            val retained = GitHubAcceptanceLedger.attach(verdict, records)
            val ledger = retained.getValue("acceptance_ledger").jsonObject
            val requirements = ledger.getValue("requirements") as JsonArray
            assertEquals(7, requirements.size)
            assertEquals(6, requirements.count { it.jsonObject["state"] == JsonPrimitive("PASS") })
            assertEquals(JsonPrimitive(false), ledger["completion_verified"])
            val fields = GitHubAcceptanceReportDiagnostics.fields(retained, retainedReport = true)
            for ((key, value) in samples) assertEquals("producer projection $key", value, fields[key])
            val context = requireNotNull(
                IssueReportContext.fromFields("CHAT_FAILURE", IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "GITHUB_TASK_INCOMPLETE", fields), time)
            )
            val diagnostic = requireNotNull(context.diagnostics)
            println(
                "POLICY_COMPOSITION " + buildJsonObject {
                    put("projected_bytes", fields.toString().encodeToByteArray().size)
                    put("diagnostic_bytes", IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).encodeToByteArray().size)
                    put("projected_policy", JsonObject(fields.filterKeys { it in samples }))
                    put("captured_policy", JsonObject(diagnostic.values.filterKeys { it in samples }))
                    put("omitted_fields", JsonArray(diagnostic.omittedFields.map(::JsonPrimitive)))
                }
            )
            assertEquals(3, diagnostic.schemaVersion)
            for ((key, value) in samples) assertEquals(key, value, diagnostic.values[key])
            assertTrue(feature in diagnostic.requiredServiceFeatures())
            assertEquals(JsonPrimitive(false), diagnostic.values["status_policy_complete"])
            assertNull(context.dispatched)
            assertNull(context.remoteAcceptance)
            assertNull(context.retryable)
            val approved = report(context).approvedPayload()
            val serialized = Json.parseToJsonElement(approved).jsonObject.getValue("context").jsonObject.getValue("diagnostics").jsonObject
            assertEquals(JsonPrimitive(3), serialized["schemaVersion"])
            for ((key, value) in samples) assertEquals(key, value, serialized.getValue("values").jsonObject[key])
            assertFalse(approved.contains("private-"))
            assertEquals(approved, IssueReport.json.decodeFromString(IssueReport.serializer(), approved).approvedPayload())
            assertEquals(3, requests)
        } finally {
            http.close()
        }
    }

    @Test fun minimalLegacySchemaRejectsAllPolicyFormsWhileSchemaThreePreservesThem() {
        val legacyContext = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE"), time))
        val legacy = requireNotNull(legacyContext.diagnostics)
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.isValid())
        assertFalse(feature in legacy.requiredServiceFeatures())
        val legacyPayload = report(legacyContext).approvedPayload()
        assertEquals(legacyPayload, IssueReport.json.decodeFromString(IssueReport.serializer(), legacyPayload).approvedPayload())
        val currentContext = requireNotNull(
            IssueReportContext.fromFields("CHAT_FAILURE", IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "GITHUB_TASK_INCOMPLETE", mapOf("diagnostic_area" to "ACCEPTANCE")), time)
        )
        val current = requireNotNull(currentContext.diagnostics)
        assertEquals(3, current.schemaVersion)
        for ((key, value) in samples) {
            assertNull("legacy value $key", legacy.copy(values = legacy.values + (key to value)).validated())
            assertNull("legacy omission $key", legacy.copy(omittedFields = listOf(key)).validated())
            assertNull("legacy unavailable $key", legacy.copy(unavailableFields = legacy.unavailableFields + key).validated())
            for (diagnostic in listOf(
                current.copy(values = current.values + (key to value)),
                current.copy(omittedFields = listOf(key)),
                current.copy(unavailableFields = current.unavailableFields + key)
            )) {
                assertTrue("schema3 $key", diagnostic.isValid())
                assertTrue(feature in diagnostic.requiredServiceFeatures())
                val approved = report(currentContext.withDiagnostics(diagnostic)).approvedPayload()
                val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), approved)
                assertEquals(diagnostic, restored.context?.diagnostics)
                assertEquals(approved, restored.approvedPayload())
                assertTrue(feature in requireNotNull(restored.context?.diagnostics).requiredServiceFeatures())
            }
        }
    }

    private fun receipt(action: String, source: JsonObject, tool: String = "github_context", additionalArguments: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("tool", tool)
        put(
            "arguments",
            buildJsonObject {
                put("action", action)
                put("branch", "private-main")
                additionalArguments.forEach { (key, value) -> put(key, value) }
            }
        )
        put("success", true)
        put("trusted", true)
        put("verification_eligible", true)
        put("repository_role", "WRITE_DESTINATION")
        put("repository", "private-owner/private-repo")
        put("observed_at_ms", time)
        put("epoch", 0)
        put("result", source)
        put("result_sha256", GitHubEvidenceBook.digest(source.toString()))
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun report(context: IssueReportContext) = IssueReport(
        "20dd6c4b-c040-4e06-9f37-8bea8a8b1293", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "chat", errorCode = "GITHUB_TASK_INCOMPLETE", context = context
    )
}
