package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings

/** Admission still verifies host tools, grants and the exact execution binding. */
internal object GatewayOwnedTurnPolicy {
    enum class Intent(val scope: String) {
        READ("read"),
        PLAN("plan"),
        IMPLEMENT("implement"),
        INTEGRATION_TEST("integration_test")
    }

    private val repositoryWork = Regex("""\b(?:repo(?:sitory)?|git(?:hub)?|code|files?|sources?|branch(?:es)?|commits?|pull requests?|prs?|ci|workflows?|builds?|tests?|bugs?|issues?|regressions?|features?|parser|app)\b""")

    fun configured(profile: PlatformV2?, context: GitHubConversationContext?, features: AppFeatureSettings): Boolean =
        profile != null &&
            context?.mainProfileUid == profile.uid &&
            !profile.disableAllTools &&
            !profile.disableRemoteTools &&
            GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile) &&
            features.githubProfileOverrides[profile.uid]?.let { it.githubTools && !it.engineeringGatewayProfileUid.isNullOrBlank() } == true

    /** Ordinary chat, reads and planning use the selected client profile. Jobs can be started explicitly in the Jobs UI. */
    fun startsNewJob(request: String): Boolean {
        val instruction = currentInstruction(request)
        return repositoryWork.containsMatchIn(instruction) && intent(request) == Intent.IMPLEMENT
    }

    /** Only the new instruction can authorize implementation; quoted history is data. */
    fun scope(request: String): String = intent(request).scope

    fun intent(request: String): Intent = when (classify(currentInstruction(request))) {
        "implement" -> Intent.IMPLEMENT
        "plan" -> Intent.PLAN
        "integration_test" -> Intent.INTEGRATION_TEST
        else -> Intent.READ
    }

    private fun currentInstruction(request: String): String {
        var fenced = false
        return request.lineSequence().map(String::trim).filter { line ->
            if (line.startsWith("```") || line.startsWith("~~~")) {
                fenced = !fenced
                false
            } else {
                !fenced && line.isNotBlank() && !line.startsWith('>') && !line.startsWith('"') && !line.startsWith('\'')
            }
        }.joinToString("\n").lowercase(java.util.Locale.ROOT)
    }

    private fun classify(instruction: String): String {
        if (Regex("\\b(?:(?:read|review|audit|inspect)[- ]only|plan(?:ning)? only|no (?:code )?(?:changes|writes))\\b").containsMatchIn(instruction) ||
            Regex("\\b(?:do not|don't|never)\\s+(?:(?:edit|modify|change|write|implement)\\b|make\\s+(?:any\\s+)?(?:code\\s+)?changes\\b)").containsMatchIn(instruction) ||
            Regex("\\bwithout\\s+(?:editing|modifying|changing|writing|implementing)\\b").containsMatchIn(instruction)
        ) {
            return if (Regex("\\b(?:plan|design|propos\\w*)\\b").containsMatchIn(instruction)) "plan" else "read"
        }
        val polite = instruction.replace(Regex("^(?:(?:please|can you|could you|would you|help me|i want you to|i need you to)\\s+)+"), "")
        val planArtifact = Regex("^(?:create|make|write|prepare|draft|build)\\s+(?:(?:a|an|the)\\s+)?(?:(?:detailed|implementation|technical|migration|testing|design)\\s+){0,3}(?:plan|design|proposal|outline|specification)\\b")
        if (planArtifact.containsMatchIn(polite) && !Regex("\\b(?:and|then)\\s+(?:implement|apply|fix|add|remove|delete|refactor)\\b").containsMatchIn(polite)) return "plan"
        if (Regex("^(?:how|what|why|where|when|which|is|are|does|did|should|would)\\b").containsMatchIn(polite)) {
            return if (Regex("^(?:how|should|would)\\b|^what\\b.*\\b(?:plan|design|approach|would|should)\\b").containsMatchIn(polite)) "plan" else "read"
        }
        if (Regex("^(?:plan|design|outline|propose|review|audit|analy[sz]e|inspect|summari[sz]e)\\b").containsMatchIn(polite) &&
            !Regex("\\b(?:and|then)\\s+(?:implement|apply|fix|build|make|add)\\b").containsMatchIn(polite)
        ) {
            return "plan"
        }
        return if (Regex("(?:^|[.!?\\n]\\s*)(?:please\\s+)?(?:implement|fix|repair|add|build|create|update|change|remove|delete|refactor|apply|merge|commit)\\b").containsMatchIn(polite) ||
            Regex("\\b(?:and|then)\\s+(?:implement|apply|fix|build|make|add)\\b").containsMatchIn(polite)
        ) {
            "implement"
        } else if (
            "[github-workflow-preset:test_integration]" in instruction ||
            Regex("\\b(?:test|exercise|verify)\\b[^.!?\\n]{0,160}\\b(?:permitted|authorized|owned|disposable)\\b[^.!?\\n]{0,80}\\b(?:mutations|writes|fixture)\\b").containsMatchIn(polite)
        ) {
            // Fixture tests are executed by the native integration harness, which
            // checks exact approvals, ownership and cleanup for each operation.
            // They do not authorize an implementation/commit job on the gateway.
            "integration_test"
        } else {
            "read"
        }
    }
}
