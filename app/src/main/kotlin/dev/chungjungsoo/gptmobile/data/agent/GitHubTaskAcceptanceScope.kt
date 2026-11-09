package dev.chungjungsoo.gptmobile.data.agent

/** Availability alone cannot turn unrelated work or account discovery into a repository task. */
internal fun requiresNativeGitHubAcceptance(request: String, hasNativeTools: Boolean): Boolean {
    if (CurrentRequestContract.from(request).conversational) return false
    val explicitGitHub = Regex("(?i)(?<![A-Za-z0-9])github(?:_[A-Za-z0-9_]+)?(?![A-Za-z0-9])").containsMatchIn(request)
    if (!hasNativeTools && !explicitGitHub) return false
    if (dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryLifecycle.intent(request) != null) return true
    val repositoryEntity = Regex("(?i)\\b(?:repos?(?:itory|itories)?|repositories|codebase|pull requests?)\\b|\\b[A-Za-z0-9_.-]+\\.(?:kt|java|py|js|ts|tsx|yml|yaml|json|xml)\\b").containsMatchIn(request)
    val repositoryScope = explicitGitHub || repositoryEntity
    val repositoryRead = repositoryEntity &&
        Regex("(?i)^(?:please\\s+)?(?:read|inspect|review|audit|show|list|fetch|open|check|verify|summari[sz]e|compare)\\b").containsMatchIn(request.trim()) &&
        !Regex("(?i)^(?:please\\s+)?(?:show(?: me)?|review|compare)\\s+(?:how|why|what)\\b").containsMatchIn(request.trim()) &&
        !Regex("(?i)\\b(?:my|accessible|available)\\s+(?:github\\s+)?(?:repos|repositories)\\b|\\brepositories\\s+(?:I|we)\\s+(?:can|have)\\b").containsMatchIn(request)
    return repositoryScope &&
        (
            dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(request) ||
                dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion.requiresRepositoryEvidence(request) ||
                repositoryRead ||
                explicitGitHub &&
                Regex("(?i)^(?:please\\s+)?(?:call|invoke|use|execute)\\b").containsMatchIn(request.trim())
            )
}

/** Decide before dispatch: an unbound task cannot later acquire repository acceptance. */
internal fun requireNativeGitHubTarget(required: Boolean, targetCount: Int, request: String = "") {
    // Creation/import selects a destination in its own schema. Requiring an
    // already accessible open repository would prevent that workflow entirely.
    if (!required || targetCount == 1 || dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryLifecycle.intent(request) != null) return
    if (targetCount == 0) {
        error("GITHUB_REPOSITORY_TARGET_REQUIRED: Select the exact repository and connection for this task before dependent work. No repository operation was dispatched.")
    } else {
        error("GITHUB_REPOSITORY_TARGET_AMBIGUOUS: More than one repository target is selected. Select the exact repository and connection for this task before dependent work. No repository operation was dispatched.")
    }
}
