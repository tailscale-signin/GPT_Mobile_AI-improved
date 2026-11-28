package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubCapabilityRequest
import dev.chungjungsoo.gptmobile.data.github.GitHubCiObservationReport
import dev.chungjungsoo.gptmobile.data.github.GitHubCodeResearch
import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion
import dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity

/** Request relevance only. A route neither grants tool access nor authorizes a mutation. */
internal data class IntelligenceIntent(val repository: Boolean, val research: Boolean) {
    companion object {
        private val researchRequest = Regex("(?i)\\b(?:search|research|browse|look (?:up|online)|sources|papers|studies|latest|news|citations)\\b")
        private val scopedSubject = Regex("(?i)\\b(?:this|our|my|selected|current)\\s+(?:repo(?:sitory)?|codebase|branch|pull request|app(?:lication)?|project)\\b")
        private val repositoryInquiry = Regex("(?i)^(?:please\\s+)?(?:what|where|which|why|how|explain|describe|inspect|review|investigate|compare|analy[sz]e|find|show|summari[sz]e)\\b")
        private val followup = Regex("(?i)^(?:please\\s+)?(?:continue|resume|retry|try again|finish(?: it| that| this)?|fix(?: it| that| this)?|go ahead|do it|proceed|push|merge|publish|check again)[.!?\\s]*$")
        private val implementationFollowup = Regex("(?i)^(?:please\\s+)?(?:implement|apply|build|add|integrate)\\s+(?:the\\s+)?(?:first|second|third|fourth|fifth|[1-9]|those|these|that|it|all|above|previous|recommended|number\\s+(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten))\\b")
        private val mixedImplementation = Regex("(?i)\\b(?:and|then)\\s+(?:please\\s+)?(?:implement|apply|integrate|fix|repair|update|add)\\b")
        private val capabilityPrefix = Regex("github_[a-z_]+(?: github_[a-z_]+)*")
        private val recordRead = Regex("(?i)^(?:please\\s+)?(?:list|inspect|review|audit|check|watch|monitor|diagnose|investigate|read|show(?: me)?)\\b")
        private val repositoryRead = Regex("(?i)^(?:please\\s+)?(?:list|inspect|review|audit|check|investigate|read|show(?: me)?)\\s+(?:(?:the|this|our|my|selected|current|all)\\s+)*(?:git|github|repos?(?:itory|itories)?|repositories|codebase|source(?: code)?)[.!?\\s]*$")
        private val repositoryName = Regex("(?i)\\b(?:git|github|repos?(?:itory|itories)?|repositories|codebase)\\b")
        private val externalDestination = Regex("(?i)\\b(?:in|into|to|on)\\s+(?:(?:the|my|our)\\s+)?(?:google drive|dropbox|onedrive|sharepoint|notion|box)\\b")
        private val explicitRepository = Regex("(?i)\\b(?:repos?(?:itory|itories)?|repositories|codebase|branches?|pull requests?|(?:prs?|issues?)\\s*#?\\d+|(?:on|in|from|for)\\s+github|github\\s+(?:issues?|prs?|commits?|releases?|workflows?))\\b")
        private val records = Regex("(?i)\\b(?:issues?|prs?|pull requests?|commits?|branches|workflows?|checks|ci|releases?|tags?)\\b")
        private val conceptual = Regex("(?i)^(?:please\\s+)?(?:show(?: me)?|review|compare)\\s+(?:how|why|what)\\b")

        internal fun externalServiceOnly(request: String): Boolean {
            val text = GitHubTaskContinuity.request(request)
            return externalDestination.containsMatchIn(text) &&
                !explicitRepository.containsMatchIn(text) &&
                !scopedSubject.containsMatchIn(text)
        }

        fun from(request: String, selectedRepository: Boolean): IntelligenceIntent {
            val text = GitHubTaskContinuity.request(request).trim()
            if (CurrentRequestContract.from(text).conversational || GitHubCapabilityRequest.from(text) != null) return IntelligenceIntent(false, false)
            val research = researchRequest.containsMatchIn(text)
            if (externalServiceOnly(text)) return IntelligenceIntent(false, research)
            val repository = capabilityPrefix.matches(text.lineSequence().firstOrNull().orEmpty()) ||
                GitHubLookupRoute.from(text) != null ||
                GitHubCiObservationReport.matches(text) ||
                requiresNativeGitHubAcceptance(text, true) ||
                repositoryRead.containsMatchIn(text) &&
                !conceptual.containsMatchIn(text) ||
                dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(text) &&
                (selectedRepository || repositoryName.containsMatchIn(text)) ||
                recordRead.containsMatchIn(text) &&
                records.containsMatchIn(text) &&
                !conceptual.containsMatchIn(text) ||
                selectedRepository &&
                (
                    GitHubCodeResearch.matches(text) ||
                        GitHubRequestCompletion.requiresRepositoryEvidence(text) ||
                        followup.matches(text) ||
                        implementationFollowup.containsMatchIn(text) ||
                        research &&
                        scopedSubject.containsMatchIn(text) &&
                        mixedImplementation.containsMatchIn(text) ||
                        !research &&
                        scopedSubject.containsMatchIn(text) &&
                        repositoryInquiry.containsMatchIn(text)
                    )
            return IntelligenceIntent(repository, research)
        }
    }
}
