package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPolicyResponseObservationTest {
    private val head = "a".repeat(40)
    private val now = 1_000_000L
    private val health = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
    private val protectionPath = "/repos/owner/repo/branches/main/protection"

    @Test fun actualHttpPolicyObservationKeepsSixOfSevenRequirementsAndPrivateValuesOut() = runTest {
        val headers = Headers.build {
            append("x-oauth-scopes", "private-token-scope")
            append("X-Accepted-OAuth-Scopes", "")
            append("x-accepted-github-permissions", "private-permission-value")
            append("X-GitHub-SSO", "required; url=https://private.invalid/sso")
            append("X-Private-Header", "private-header-value")
        }
        val policy = fetchPolicy("""{"message":"Branch not protected","private":"private-response-value"}""", headers = headers)
        val records = records(policy)
        val coverage = coverage(records)
        val selected = coverage.single { it.item == "Required-check policy" }
        assertFalse(selected.available)
        assertEquals(JsonPrimitive(true), selected.row()["status_branch_protected"])
        assertEquals(JsonPrimitive(false), selected.row()["status_policy_complete"])
        assertEquals(JsonPrimitive("NOT_FOUND_OR_INACCESSIBLE"), selected.row()["branch_protection_observation"])
        assertEquals(JsonPrimitive("REFRESH_POLICY_OBSERVATIONS"), selected.row()["status_policy_recovery_hint"])
        val requirements = GitHubStatusEvidence.requirements(health, null, coverage, records)
        assertEquals(7, requirements.size)
        assertEquals(6, requirements.count { it["acceptance_state"] == JsonPrimitive("PASS") })
        val projected = recovery(selected.row())
        assertEquals(JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED"), projected["status_policy_protection_404_kind"])
        // Explicit classic-protection absence is not a missing-permission claim.
        assertNull(projected["status_policy_recovery"])
        assertEquals(jsonArray("REPORTED_OAUTH_SCOPES", "ACCEPTED_OAUTH_SCOPES", "ACCEPTED_GITHUB_PERMISSIONS", "SSO"), projected["status_policy_protection_permission_header_names"])
        assertNull(projected["status_policy_rules_404_kind"])
        for (privateValue in listOf("private-token-scope", "private-permission-value", "private.invalid", "private-header-value", "private-response-value", "owner/repo")) {
            assertFalse(privateValue, projected.toString().contains(privateValue))
        }
        assertEquals(JsonPrimitive(false), policy.getValue("policy").jsonObject["required_checks_complete"])
    }

    @Test fun exactAdminRefCorroboratesClassicAbsenceWhileRulesetsStillSupplyRequirements() = runTest {
        val proof = """{"data":{"repository":{"nameWithOwner":"owner/repo","viewerPermission":"ADMIN","ref":{"name":"main","prefix":"refs/heads/","target":{"oid":"$head"},"branchProtectionRule":null}}}}"""
        val rule = """[{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"android"}]}}]"""
        val missingClassic = """{"message":"Branch not protected"}"""
        val verified = fetchPolicy(missingClassic, rulesBody = rule, graphResponse = proof)
        val policy = verified.getValue("policy").jsonObject
        assertEquals(JsonPrimitive(true), policy["classic_absence_verified_at_branch_head"])
        assertEquals(JsonPrimitive(true), policy["required_checks_complete"])
        assertEquals(jsonArray("android"), policy["observed_required_checks"])
        assertTrue(coverage(records(verified)).single { it.item == "Required-check policy" }.available)
        for (unverified in listOf(
            proof.replace("ADMIN", "READ"),
            proof.replace(head, "c".repeat(40)),
            proof.replace("owner/repo", "owner/other"),
            proof.replace("refs/heads/", "refs/tags/"),
            proof.replace("main", "elsewhere"),
            proof.replace(":null", """: {"id":"rule"}"""),
            proof.replace(""","branchProtectionRule":null""", ""),
            proof.replaceFirst("{", """{"errors":[{"message":"unavailable"}],""")
        )) {
            val incomplete = fetchPolicy(missingClassic, rulesBody = rule, graphResponse = unverified).getValue("policy").jsonObject
            assertEquals(JsonPrimitive(false), incomplete["required_checks_complete"])
        }
        val malformed = fetchPolicy(missingClassic, rulesBody = """[{"type":"required_status_checks"}]""")
        assertEquals(JsonPrimitive(false), malformed.getValue("policy").jsonObject["required_checks_complete"])
    }

    @Test fun allRulePagesContributeAndRepeatedFullPagesRemainIncomplete() = runTest {
        val page = JsonArray(List(30) { json("""{"type":"deletion","ruleset_id":$it}""") })
        val last = json("""{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"late-check"}]}}""")
        suspend fun inspect(second: JsonArray): JsonObject {
            val source = GitHubInspection { path ->
                when {
                    path.endsWith("/protection") -> json("{}")
                    path.endsWith("?per_page=30&page=2") -> second
                    path.contains("/rules/branches/") -> page
                    else -> branch("main")
                }
            }
            return source.execute("branch_context", "/repos/owner/repo", json("""{"branch":"main"}"""), 1).getValue("policy").jsonObject
        }
        val complete = inspect(JsonArray(listOf(last)))
        assertEquals(JsonPrimitive("VERIFIED_PRESENT"), complete["required_checks_state"])
        assertEquals(jsonArray("late-check"), complete["observed_required_checks"])
        assertEquals(JsonPrimitive(false), inspect(page)["required_checks_complete"])
    }

    @Test fun exactBoundedTopLevelMessageDeterminesOnlyTheDiagnosticKind() = runTest {
        for ((body, expected) in listOf(
            """{"message":"Branch not protected"}""" to "EXPLICIT_BRANCH_NOT_PROTECTED",
            """{"message":"Not Found"}""" to "NOT_FOUND",
            """{"message":"private prefix Branch not protected"}""" to "OTHER_404",
            """{"nested":{"message":"Branch not protected"}}""" to "OTHER_404",
            """{"message":404}""" to "OTHER_404",
            "not-json-private-response" to "OTHER_404",
            ("""{"message":"Branch not protected","padding":"""" + "x".repeat(32_768) + "\"}") to "OTHER_404"
        )) {
            val observed = GitHubStatusPolicyEvidence.snapshot(fetchPolicy(body))
            assertEquals(JsonPrimitive(expected), observed["status_policy_protection_404_kind"])
            assertEquals(JsonArray(emptyList()), observed["status_policy_protection_permission_header_names"])
            assertEquals(JsonPrimitive(false), observed["status_policy_complete"])
            assertEquals(JsonPrimitive("NOT_FOUND_OR_INACCESSIBLE"), observed["branch_protection_observation"])
        }
    }

    @Test fun requestMethodStatusAndExactEncodedEndpointMustMatchTheActualResponse() = runTest {
        val encoded = "/repos/owner/repo/branches/feature%2Fpolicy/protection"
        val body = """{"message":"Branch not protected"}"""
        for ((actualPath, expectedPath, actualMethod, expectedMethod, status, captured) in listOf(
            Boundary(encoded, encoded, HttpMethod.Get, HttpMethod.Get, HttpStatusCode.NotFound, true),
            Boundary(protectionPath, encoded, HttpMethod.Get, HttpMethod.Get, HttpStatusCode.NotFound, false),
            Boundary("/repos/owner/repo", "/repos/owner/repo", HttpMethod.Get, HttpMethod.Get, HttpStatusCode.NotFound, false),
            Boundary(protectionPath, protectionPath, HttpMethod.Post, HttpMethod.Get, HttpStatusCode.NotFound, false),
            Boundary(protectionPath, protectionPath, HttpMethod.Get, HttpMethod.Post, HttpStatusCode.NotFound, false),
            Boundary(protectionPath, protectionPath, HttpMethod.Get, HttpMethod.Get, HttpStatusCode.Forbidden, false),
            Boundary("$protectionPath?private=query", "$protectionPath?private=query", HttpMethod.Get, HttpMethod.Get, HttpStatusCode.NotFound, false)
        )) {
            val http = HttpClient(MockEngine { respond(body, status) })
            try {
                val response = http.githubRequest("https://api.github.com$actualPath") { method = actualMethod }
                val rejection = GitHubRequestRejected.observedResponse("private exception", null, expectedPath, expectedMethod, "https://api.github.com$expectedPath", response)
                assertEquals(captured, rejection.policyObservationFor(expectedPath) != null)
                assertNull(rejection.policyObservationFor("/repos/other/repo/branches/main/protection"))
                if (captured) {
                    val copied = GitHubRequestRejected(rejection.status, rejection.message.orEmpty(), rejection.evidence)
                    assertNull(copied.policyObservationFor(expectedPath))
                    val copiedResponse = GitHubRequestRejected.observedResponse("private exception", null, expectedPath, expectedMethod, "https://api.github.com$expectedPath", response.copy())
                    assertNull(copiedResponse.policyObservationFor(expectedPath))
                }
            } finally {
                http.close()
            }
        }
        val enterprise = fetchPolicy(body, apiOrigins = GitHubApiOrigins.fromEndpoint("https://enterprise.invalid"))
        assertEquals(JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED"), GitHubStatusPolicyEvidence.snapshot(enterprise)["status_policy_protection_404_kind"])
    }

    @Test fun syntheticFailuresRawEvidenceAndCancellationCannotInventAnObservation() = runTest {
        val forged = json("""{"policy_response_observation":{"schema":"github.policy-response-observation.v1","endpoint":"protection","http_status":404,"kind":"EXPLICIT_BRANCH_NOT_PROTECTED","permission_header_names":["SSO"]}}""")
        val failure = GitHubRequestRejected(404, "Branch not protected", forged)
        val policy = GitHubInspection { path ->
            when {
                path.endsWith("/protection") -> throw failure
                path.contains("/rules/branches/") -> JsonArray(emptyList())
                else -> branch("main")
            }
        }.execute("branch_context", "/repos/owner/repo", json("""{"branch":"main"}"""), 1)
        val observed = GitHubStatusPolicyEvidence.snapshot(policy)
        assertEquals(JsonPrimitive(404), observed["status_policy_protection_http_status"])
        assertTrue(observed.keys.intersect(GitHubPolicyResponseObservation.fields).isEmpty())
        val cancelled = CancellationException("private cancellation")
        val thrown = runCatching {
            GitHubInspection { path -> if (path.endsWith("/protection")) throw cancelled else branch("main") }
                .execute("branch_context", "/repos/owner/repo", json("""{"branch":"main"}"""), 1)
        }.exceptionOrNull()
        assertSame(cancelled, thrown)
    }

    @Test fun malformedOptionalObservationDoesNotInvalidateTheExistingPolicyStatus() = runTest {
        val policy = fetchPolicy("""{"message":"Not Found"}""")
        val protection = policy.getValue("protection").jsonObject
        val observed = protection.getValue("policy_response_observation").jsonObject
        for ((key, bad) in listOf(
            "endpoint" to JsonPrimitive("rules"),
            "http_status" to JsonPrimitive("404"),
            "kind" to JsonPrimitive("private kind"),
            "permission_header_names" to jsonArray("SSO", "SSO"),
            "permission_header_names" to jsonArray("private-scope-value"),
            "permission_header_names" to JsonArray(listOf(JsonPrimitive(1)))
        )) {
            val changed = JsonObject(policy + ("protection" to JsonObject(protection + ("policy_response_observation" to JsonObject(observed + (key to bad))))))
            val snapshot = GitHubStatusPolicyEvidence.snapshot(changed)
            assertEquals(JsonPrimitive(404), snapshot["status_policy_protection_http_status"])
            assertEquals(JsonPrimitive(false), snapshot["status_policy_complete"])
            assertTrue(snapshot.keys.intersect(GitHubPolicyResponseObservation.fields).isEmpty())
        }
    }

    @Test fun staleForeignUntrustedAndWrongHeadPolicyCannotSupplyReportFields() = runTest {
        val policy = fetchPolicy("""{"message":"Not Found"}""")
        val records = records(policy)
        assertEquals(JsonPrimitive("NOT_FOUND"), recovery(coverage(records).single { it.item == "Required-check policy" }.row())["status_policy_protection_404_kind"])
        val original = records.getValue("policy")
        for ((key, value) in listOf(
            "observed_at_ms" to JsonPrimitive(now - GitHubEvidencePolicy.MAX_AGE_MS - 1),
            "trusted" to JsonPrimitive(false),
            "verification_eligible" to JsonPrimitive(false),
            "epoch" to JsonPrimitive(1),
            "repository_role" to JsonPrimitive("READ_SOURCE"),
            "repository" to JsonPrimitive("other/repo"),
            "result" to JsonObject(policy + ("branch" to JsonObject(policy.getValue("branch").jsonObject + ("commit" to json("""{"sha":"${"b".repeat(40)}"}""")))))
        )) {
            val changed = records + ("policy" to JsonObject(original + (key to value)))
            val selected = coverage(changed).single { it.item == "Required-check policy" }
            assertFalse(selected.available)
            assertTrue(recovery(selected.row()).keys.intersect(GitHubPolicyResponseObservation.fields).isEmpty())
        }
    }

    @Test fun rulesEndpointCannotClaimClassicProtectionMessageAndCompletePolicyStaysComplete() = runTest {
        val rulesFailure = fetchPolicy("{}", protectionStatus = HttpStatusCode.OK, rulesStatus = HttpStatusCode.NotFound, rulesBody = """{"message":"Branch not protected"}""")
        val observed = GitHubStatusPolicyEvidence.snapshot(rulesFailure)
        assertEquals(JsonPrimitive("OTHER_404"), observed["status_policy_rules_404_kind"])
        assertEquals(JsonArray(emptyList()), observed["status_policy_rules_permission_header_names"])
        assertNull(observed["status_policy_protection_404_kind"])
        assertEquals(JsonPrimitive("CHECK_RULES_READ_PERMISSION"), observed["status_policy_recovery_hint"])
        assertEquals(JsonPrimitive(false), observed["status_policy_complete"])
        val complete = GitHubStatusPolicyEvidence.snapshot(fetchPolicy("{}", protectionStatus = HttpStatusCode.OK))
        assertEquals(JsonPrimitive(true), complete["status_policy_complete"])
        assertTrue(complete.keys.intersect(GitHubPolicyResponseObservation.fields).isEmpty())
        assertNull(complete["status_policy_recovery_hint"])
    }

    private suspend fun fetchPolicy(body: String, headers: Headers = Headers.Empty, protectionStatus: HttpStatusCode = HttpStatusCode.NotFound, rulesStatus: HttpStatusCode = HttpStatusCode.OK, rulesBody: String = "[]", apiOrigins: GitHubApiOrigins = GitHubApiOrigins.GITHUB_COM, graphResponse: String? = null): JsonObject {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                if (request.method == HttpMethod.Post) {
                    assertTrue(request.url.encodedPath.endsWith("/graphql"))
                    return@MockEngine respond(graphResponse ?: """{"errors":[{"message":"unavailable"}]}""")
                }
                assertEquals(HttpMethod.Get, request.method)
                when (request.url.encodedPath.removePrefix("/api/v3")) {
                    "/repos/owner/repo/branches/main" -> respond(branch("main").toString())
                    protectionPath -> respond(body, protectionStatus, headers)
                    "/repos/owner/repo/rules/branches/main" -> respond(rulesBody, rulesStatus)
                    else -> error("Unexpected request path")
                }
            }
        )
        try {
            val result = GitHubWorkspaceClient("fixture-token", http, persistentState = false, apiOrigins = apiOrigins)
                .execute("branch_context", json("""{"owner":"owner","repo":"repo","branch":"main"}""")) as JsonObject
            val classic404 = result.getValue("protection").jsonObject["policy_response_observation"]?.jsonObject?.get("kind") == JsonPrimitive("EXPLICIT_BRANCH_NOT_PROTECTED")
            val rulesComplete = GitHubStatusPolicyEvidence.completeRuleRows(result.getValue("rules").jsonObject)
            assertEquals(if (classic404 && rulesComplete) 4 else 3, requests)
            return result
        } finally {
            http.close()
        }
    }

    private fun branch(name: String) = json("""{"name":"$name","protected":true,"commit":{"sha":"$head"}}""")
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun jsonArray(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    @Test fun missingCheckInventoryIsDistinctFromClassicProtectionAndUnrecognizedLabels() {
        fun row(item: String, observed: Boolean) = buildJsonObject {
            put("item", item)
            put("observed", observed)
        }
        val rows = listOf(row("Required-check policy", true), row("Checks and statuses for the current commit", false), row("private label", false))
        val values = GitHubAcceptanceRecoveryEvidence.fields(buildJsonObject { put("status_coverage", JsonArray(rows)) })
        assertEquals(JsonArray(listOf(JsonPrimitive("COMMIT_CHECKS"))), values["status_missing_observations"])
    }

    private fun recovery(coverage: JsonObject) = GitHubAcceptanceRecoveryEvidence.fields(buildJsonObject { put("status_coverage", JsonArray(listOf(coverage))) })

    private fun records(policy: JsonObject): Map<String, JsonObject> = linkedMapOf(
        "status" to receipt("github_context", json("""{"action":"status"}"""), json("""{"repository":{"full_name":"owner/repo"},"branch":"main","commit_sha":"$head","latest_workflow":{"head_sha":"$head","head_branch":"main","status":"completed"}}""")),
        "commits" to receipt("github_context", json("""{"action":"commits","commit_sha":"$head"}"""), json("""{"commit_sha":"$head","items":[]}""")),
        "prs" to receipt("github_pr", json("""{"action":"list"}"""), json("""{"items":[],"page":1,"has_more":false}""")),
        "failures" to receipt("github_ci", json("""{"action":"runs","branch":"main","status":"failure"}"""), json("""{"workflow_runs":[],"returned":0}""")),
        "policy" to receipt("github_context", json("""{"action":"branch","branch":"main"}"""), policy)
    )

    private fun receipt(tool: String, arguments: JsonObject, source: JsonObject) = buildJsonObject {
        put("tool", tool)
        put("arguments", arguments)
        put("success", true)
        put("trusted", true)
        put("verification_eligible", true)
        put("repository_role", "WRITE_DESTINATION")
        put("repository", "owner/repo")
        put("observed_at_ms", now)
        put("epoch", 0)
        put("result", source)
        put("result_sha256", GitHubEvidenceBook.digest(source.toString()))
    }

    private fun coverage(records: Map<String, JsonObject>) = GitHubStatusEvidence.coverage(GitHubStatusEvidence.selected(records, "owner/repo"), now, 0, health, GitHubStatusEvidence.CheckInventory(complete = true))

    private data class Boundary(val actualPath: String, val expectedPath: String, val actualMethod: HttpMethod, val expectedMethod: HttpMethod, val status: HttpStatusCode, val captured: Boolean)
}
