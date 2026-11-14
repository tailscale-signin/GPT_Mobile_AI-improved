package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizationIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubCommitDetail
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceOrigin
import dev.chungjungsoo.gptmobile.data.github.GitHubIntegrationAcceptance
import dev.chungjungsoo.gptmobile.data.github.GitHubReadRetry
import dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
import dev.chungjungsoo.gptmobile.data.github.GitHubRefAvailability
import dev.chungjungsoo.gptmobile.data.github.GitHubReleaseEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.gitHubCheckSummary
import dev.chungjungsoo.gptmobile.data.github.gitHubPullRequestHealth
import dev.chungjungsoo.gptmobile.data.github.normalizeGitHubBranch
import dev.chungjungsoo.gptmobile.data.github.normalizeGitHubRepository
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Disposable production-policy fixtures; these never contact or mutate GitHub. */
internal class AppAuditGitHubContractSource @Inject constructor() :
    AppAuditFixtureSource(
        AppAuditGitHubCatalog.CONTRACT_SOURCE,
        AppAuditGitHubCatalog.contracts.map { check ->
            AppAuditFixture(check.id, check.label, listOf("github")) { exerciseGitHubContract(check.id) }
        }
    )

private suspend fun AppAuditAssertions.exerciseGitHubContract(id: String) {
    val sha = "a".repeat(40)
    when (id) {
        "github_read_authority_v1" -> {
            verify(GitHubAuthorizationBoundary.isReplayableRead("github", json("""{"action":"get_repository"}""")))
            verify(!GitHubAuthorizationBoundary.isReplayableRead("github", json("""{"action":"merge_pull_request"}""")))
            verify(!GitHubAuthorizationBoundary.isReplayableRead("untrusted_plugin", json("""{"action":"get_repository"}""")))
        }
        "github_identity_scope_v1" -> {
            val scope = GitHubAuthorizationIdentity.scope("account", "connection")
            verify(scope != GitHubAuthorizationIdentity.scope("another", "connection"))
            verify(scope != GitHubAuthorizationIdentity.scope("account", "another"))
            verify(scope != GitHubAuthorizationIdentity.scope("account", "connection", host = "enterprise.example"))
            verify(GitHubAuthorizationIdentity.permission("OAUTH", "READ_ONLY", "", null) != GitHubAuthorizationIdentity.permission("OAUTH", "ASK_WRITES", "", null))
        }
        "github_repository_input_v1" -> {
            verify(normalizeGitHubRepository(null, "https://github.com/audit/fixture.git") == ("audit" to "fixture"))
            verify(runCatching { normalizeGitHubRepository("other", "audit/fixture") }.isFailure)
            verify(normalizeGitHubBranch("refs/heads/feature/audit") == "feature/audit")
            for (invalid in listOf("HEAD", "main~1", "refs/tags/v1", "../main")) verify(runCatching { normalizeGitHubBranch(invalid) }.isFailure)
        }
        "github_ref_isolation_v1" -> {
            val refs = GitHubRefAvailability(now = { 1_000 })
            val path = "/repos/audit/fixture/commits/missing"
            refs.failed(path, 404, "Fixture only")
            val failure = runCatching { refs.requireAvailable(path) }.exceptionOrNull() as? GitHubReadValidation
            verify(failure?.code == "GITHUB_REF_UNAVAILABLE")
            verify(failure?.details?.get("automatic_branch_substitution") == JsonPrimitive(false))
            refs.requireAvailable("/repos/audit/other/commits/missing")
            refs.succeeded(path)
            verify(runCatching { refs.requireAvailable(path) }.isSuccess)
        }
        "github_path_boundary_v1" -> {
            verify(GitHubWorkspaceClient.filePath("src/one file.kt") == "src/one%20file.kt")
            for (invalid in listOf("../secret", "/absolute", "a//b", "a/./b", "a\\b")) verify(runCatching { GitHubWorkspaceClient.filePath(invalid) }.isFailure)
        }
        "github_commit_page_v1" -> {
            val input = json("""{"sha":"$sha","files":[{"filename":"fixture.kt","patch":"private fixture body"}]}""")
            val result = GitHubCommitDetail.present(input, 1, 1, includePatches = false)
            verify(result["commit_sha"] == JsonPrimitive(sha))
            verify(result["has_more"] == JsonPrimitive(true))
            verify(result["continuation"]?.jsonObject?.get("page") == JsonPrimitive(2))
            verify("private fixture body" !in result.toString())
        }
        "github_patch_approval_v1" -> {
            verify(GitHubAuthorizationBoundary.isLocalPatchStaging("github", json("""{"action":"stage_patch"}""")))
            verify(!GitHubAuthorizationBoundary.isLocalPatchStaging("github", json("""{"action":"stage_patch","commit_immediately":true}""")))
            verify(!GitHubAuthorizationBoundary.isLocalPatchStaging("github", json("""{"action":"stage_patch","commit_immediately":"false"}""")))
        }
        "github_elevated_action_v1" -> {
            verify(GitHubAuthorizationBoundary.requiresElevatedAuthorization("github", json("""{"action":"delete_repository"}""")))
            verify(GitHubAuthorizationBoundary.requiresElevatedAuthorization("github", json("""{"action":"update_branch_protection"}""")))
            verify(!GitHubAuthorizationBoundary.requiresElevatedAuthorization("github", json("""{"action":"get_repository"}""")))
        }
        "github_check_inventory_v1" -> {
            val status = json("""{"total_count":0,"state":"success"}""")
            verify(gitHubCheckSummary(json("""{"total_count":0,"check_runs":[]}"""), status) == "NONE")
            verify(gitHubCheckSummary(json("""{"total_count":2,"check_runs":[{"status":"completed","conclusion":"success"}]}"""), status) == "UNKNOWN")
            verify(gitHubCheckSummary(json("""{"total_count":1,"check_runs":[{"status":"completed","conclusion":"cancelled"}]}"""), status) == "FAILED")
        }
        "github_merge_binding_v1" -> {
            verify(gitHubPullRequestHealth(mergeResponse(sha), sha).eligible)
            verify(!gitHubPullRequestHealth(mergeResponse(sha, more = true), sha).eligible)
            verify(!gitHubPullRequestHealth(mergeResponse(sha, draft = true), sha).eligible)
            verify(gitHubPullRequestHealth(mergeResponse(sha), "b".repeat(40)).state == "STALE")
        }
        "github_api_origin_v1" -> {
            val origins = GitHubApiOrigins.fromEndpoint("https://enterprise.example/api/v3")
            verify(origins.acceptsApiUrl("https://enterprise.example/api/v3/repos/audit/fixture"))
            verify(!origins.acceptsApiUrl("https://other.example/api/v3/repos/audit/fixture"))
            verify(!origins.acceptsApiUrl("https://enterprise.example/api/v30/repos/audit/fixture"))
            verify(runCatching { GitHubApiOrigins.fromEndpoint("https://fixture:secret@api.github.com") }.isFailure)
        }
        "github_release_identity_v1" -> {
            val bytes = fixtureArchive("receipt.json")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            verify(GitHubReleaseEvidence.archive(bytes, "receipt.json", digest) == "{}")
            verify(runCatching { GitHubReleaseEvidence.archive(bytes, "receipt.json", "0".repeat(64)) }.isFailure)
            verify(!GitHubReleaseEvidence.verify(JsonObject(emptyMap()), JsonObject(emptyMap()), JsonObject(emptyMap()), "audit/fixture", sha))
        }
        "github_retry_policy_v1" -> {
            verify(GitHubReadRetry.transientStatus(503))
            verify(!GitHubReadRetry.transientStatus(401) && !GitHubReadRetry.transientStatus(403))
            verify(GitHubReadRetry.retryAfterMillis("120", now = 0) == 120_000L)
            verify(GitHubReadRetry.retryAfterMillis("invalid", now = 0) == null)
        }
        "github_dispatch_receipt_v1" -> {
            val candidate = AgentToolResult("audit-fixture", ToolResultContent.Text("Fixture only"), false)
            val absent = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            verify(absent.finish(candidate, false).isError)
            val interrupted = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            withContext(interrupted) { GitHubDispatchAccounting.started(false) }
            val receipt = interrupted.finish(candidate, false)
            verify(receipt.isError && receipt.dispatched)
            verify(receipt.executionReceipt?.get("live_read_status") == JsonPrimitive("RESPONSE_NOT_FULLY_OBSERVED"))
        }
        "github_evidence_origin_v1" -> {
            verify(GitHubEvidenceOrigin.classify(false, false, null) == "NOT_TESTED")
            verify(GitHubEvidenceOrigin.classify(true, true, json("""{"read_requests":1}""")) == "CACHED")
            verify(GitHubEvidenceOrigin.of(json("""{"evidence_origin":"MOCKED","dispatched":true}""")) == "MOCKED")
            verify(GitHubEvidenceOrigin.classify(true, false, json("""{"read_requests":1}""")) == "LIVE")
        }
        "github_runtime_binding_v1" -> {
            val runtime = json("""{"identity_source":"RUNNING_PROCESS_BUILD_CONFIG","application_id":"audit.fixture","version_name":"1.0","version_code":1,"app_source_sha":"$sha"}""")
            val receipt = buildJsonObject {
                put("tool", "github_context")
                put("arguments", json("""{"action":"status"}"""))
                put("trusted", true)
                put("success", true)
                put("evidence_origin", "LIVE")
                put("runtime_identity", runtime)
                put("repository", "audit/fixture")
                put("receipt_id", "fixture-receipt")
                put("result", json("""{"commit_sha":"$sha","branch":"fixture"}"""))
            }
            verify(GitHubIntegrationAcceptance.binding(listOf(receipt), runtime)["verified"] == JsonPrimitive(true))
            verify(GitHubIntegrationAcceptance.binding(emptyList(), runtime)["verified"] == JsonPrimitive(false))
            verify(GitHubIntegrationAcceptance.binding(listOf(JsonObject(receipt + ("trusted" to JsonPrimitive(false)))), runtime)["verified"] == JsonPrimitive(false))
        }
        else -> error("Unknown GitHub contract")
    }
}

private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

private fun mergeResponse(sha: String, more: Boolean = false, draft: Boolean = false) = json(
    """{"data":{"repository":{"pullRequest":{"state":"OPEN","isDraft":$draft,"headRefOid":"$sha","baseRefOid":"${"b".repeat(40)}","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","reviewDecision":"APPROVED","commits":{"nodes":[{"commit":{"oid":"$sha","statusCheckRollup":{"state":"SUCCESS","contexts":{"totalCount":1,"pageInfo":{"hasNextPage":$more},"nodes":[{"__typename":"CheckRun","name":"fixture","status":"COMPLETED","conclusion":"SUCCESS","isRequired":true}]}}}}]}}}}}"""
)

private fun fixtureArchive(name: String): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(name))
        zip.write("{}".encodeToByteArray())
        zip.closeEntry()
    }
}.toByteArray()
