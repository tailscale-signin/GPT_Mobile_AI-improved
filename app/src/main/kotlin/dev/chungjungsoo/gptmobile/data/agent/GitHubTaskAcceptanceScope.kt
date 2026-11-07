package dev.chungjungsoo.gptmobile.data.agent

/** Availability alone cannot turn unrelated work or account discovery into a repository task. */
internal fun requiresNativeGitHubAcceptance(request: String, hasNativeTools: Boolean): Boolean {
    if (!hasNativeTools || CurrentRequestContract.from(request).conversational) return false
    val repositoryScope = Regex("(?i)\\b(?:github|repos?(?:itory|itories)?|repositories|codebase|pull requests?)\\b|\\b[A-Za-z0-9_.-]+\\.(?:kt|java|py|js|ts|tsx|yml|yaml|json|xml)\\b").containsMatchIn(request)
    return repositoryScope &&
        (dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(request) || dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion.requiresRepositoryEvidence(request))
}

/** Decide before dispatch: an unbound task cannot later acquire repository acceptance. */
internal fun requireNativeGitHubTarget(required: Boolean, targetCount: Int) {
    if (!required || targetCount == 1) return
    if (targetCount == 0) {
        error("GITHUB_REPOSITORY_TARGET_REQUIRED: Select the exact repository and connection for this task before dependent work. No repository operation was dispatched.")
    } else {
        error("GITHUB_REPOSITORY_TARGET_AMBIGUOUS: More than one repository target is selected. Select the exact repository and connection for this task before dependent work. No repository operation was dispatched.")
    }
}
