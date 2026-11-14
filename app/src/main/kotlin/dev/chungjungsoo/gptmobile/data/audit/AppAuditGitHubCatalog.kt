package dev.chungjungsoo.gptmobile.data.audit

internal enum class AppAuditGitHubCategory(val id: String, val title: String) {
    AUTHORIZATION("github.authorization", "Authorization and account"),
    REPOSITORY("github.repository", "Repositories and references"),
    CONTENT("github.content", "Content and pagination"),
    CHANGES("github.changes", "Changes and approvals"),
    CHECKS("github.checks", "CI and merge rules"),
    ARTIFACTS("github.artifacts", "Releases and artifacts"),
    RECOVERY("github.recovery", "Recovery and request limits"),
    EVIDENCE("github.evidence", "Evidence and provenance")
}

internal data class AppAuditGitHubCheck(
    val id: String,
    val label: String,
    val category: AppAuditGitHubCategory,
    val action: String? = null
)

internal object AppAuditGitHubCatalog {
    const val CONTRACT_SOURCE = "github-contracts"
    const val LIVE_SOURCE = "github-live"
    const val MAX_CONNECTIONS = 2
    const val PAGE_SIZE = 10
    const val MAX_REQUESTS_PER_READ = 3

    val contracts = listOf(
        AppAuditGitHubCheck("github_read_authority_v1", "GitHub read and write authorization boundaries", AppAuditGitHubCategory.AUTHORIZATION),
        AppAuditGitHubCheck("github_identity_scope_v1", "GitHub account, connection and host isolation", AppAuditGitHubCategory.AUTHORIZATION),
        AppAuditGitHubCheck("github_repository_input_v1", "GitHub repository and branch validation", AppAuditGitHubCategory.REPOSITORY),
        AppAuditGitHubCheck("github_ref_isolation_v1", "GitHub unavailable refs retain their exact scope", AppAuditGitHubCategory.REPOSITORY),
        AppAuditGitHubCheck("github_path_boundary_v1", "GitHub file paths reject traversal", AppAuditGitHubCategory.CONTENT),
        AppAuditGitHubCheck("github_commit_page_v1", "GitHub commit pages retain continuation and patch scope", AppAuditGitHubCategory.CONTENT),
        AppAuditGitHubCheck("github_patch_approval_v1", "GitHub patch staging cannot silently publish", AppAuditGitHubCategory.CHANGES),
        AppAuditGitHubCheck("github_elevated_action_v1", "GitHub administrative actions retain exact approval", AppAuditGitHubCategory.CHANGES),
        AppAuditGitHubCheck("github_check_inventory_v1", "GitHub empty, partial and failed checks stay distinct", AppAuditGitHubCategory.CHECKS),
        AppAuditGitHubCheck("github_merge_binding_v1", "GitHub merge qualification requires current complete evidence", AppAuditGitHubCategory.CHECKS),
        AppAuditGitHubCheck("github_api_origin_v1", "GitHub metadata and artifact origins remain fixed", AppAuditGitHubCategory.ARTIFACTS),
        AppAuditGitHubCheck("github_release_identity_v1", "GitHub release evidence digest and source binding", AppAuditGitHubCategory.ARTIFACTS),
        AppAuditGitHubCheck("github_retry_policy_v1", "GitHub retry classification honors server cooldowns", AppAuditGitHubCategory.RECOVERY),
        AppAuditGitHubCheck("github_dispatch_receipt_v1", "GitHub interrupted reads cannot become live successes", AppAuditGitHubCategory.RECOVERY),
        AppAuditGitHubCheck("github_evidence_origin_v1", "GitHub cached, mocked and live evidence remain separate", AppAuditGitHubCategory.EVIDENCE),
        AppAuditGitHubCheck("github_runtime_binding_v1", "GitHub build binding rejects absent or untrusted receipts", AppAuditGitHubCategory.EVIDENCE)
    )
    val live = listOf(
        AppAuditGitHubCheck("github_live_account_v1", "Live GitHub account response", AppAuditGitHubCategory.AUTHORIZATION, "get_account"),
        AppAuditGitHubCheck("github_live_repository_v1", "Live selected repository metadata", AppAuditGitHubCategory.REPOSITORY, "get_repository"),
        AppAuditGitHubCheck("github_live_ref_v1", "Live selected ref and first commit-file page", AppAuditGitHubCategory.CONTENT, "get_commit_detail"),
        AppAuditGitHubCheck("github_live_pulls_v1", "Live selected repository pull requests · first page", AppAuditGitHubCategory.CHANGES, "list_open_pull_requests"),
        AppAuditGitHubCheck("github_live_checks_v1", "Live selected ref check inventory · first page", AppAuditGitHubCategory.CHECKS, "get_commit_checks"),
        AppAuditGitHubCheck("github_live_releases_v1", "Live selected repository releases · first page", AppAuditGitHubCategory.ARTIFACTS, "list_releases")
    )
    val external = listOf(
        AppAuditGitHubCheck("github_mutation_execution_v1", "GitHub write, approval and cleanup execution", AppAuditGitHubCategory.CHANGES),
        AppAuditGitHubCheck("github_merge_execution_v1", "GitHub protected merge and full workflow execution", AppAuditGitHubCategory.CHECKS),
        AppAuditGitHubCheck("github_artifact_execution_v1", "GitHub artifact download, install and device execution", AppAuditGitHubCategory.ARTIFACTS),
        AppAuditGitHubCheck("github_live_recovery_v1", "GitHub live interruption and recovery execution", AppAuditGitHubCategory.RECOVERY)
    )
    val labels = (contracts + live + external).associate { it.id to it.label } +
        ("github_target_limit_v1" to "Additional GitHub connections outside this audit budget")
    val reasonCodes = setOf(
        "GITHUB_AUDIT_CONNECTION_REQUIRED", "GITHUB_AUDIT_REPOSITORY_REQUIRED", "GITHUB_AUDIT_AUTHORIZATION_REQUIRED",
        "GITHUB_AUDIT_DISABLED", "GITHUB_AUDIT_SCOPE_INVALID", "GITHUB_AUDIT_RATE_LIMITED", "GITHUB_AUDIT_ACCESS_DENIED",
        "GITHUB_AUDIT_READ_BLOCKED", "GITHUB_AUDIT_REQUEST_LIMIT", "GITHUB_AUDIT_TARGET_LIMIT", "GITHUB_AUDIT_RESOURCE_UNAVAILABLE",
        "GITHUB_AUDIT_TRANSPORT_UNAVAILABLE", "GITHUB_AUDIT_LIVE_RESPONSE_REQUIRED", "GITHUB_AUDIT_RESPONSE_INVALID", "GITHUB_AUDIT_READ_VERIFIED"
    )

    fun category(entry: AppAuditPlanEntry): AppAuditGitHubCategory? = when {
        entry.sourceId in setOf(CONTRACT_SOURCE, LIVE_SOURCE) -> (contracts + live + external).firstOrNull { it.id == entry.probeId }?.category
            ?: AppAuditGitHubCategory.EVIDENCE
        entry.sourceId == "local-contracts" && entry.probeId == "github-ref-admission" -> AppAuditGitHubCategory.REPOSITORY
        entry.sourceId == "source-inventory" && entry.componentId == "github" -> AppAuditGitHubCategory.EVIDENCE
        else -> null
    }
}
