package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.isLocalPlatform
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination

/** Derived from the current profile, so restored settings cannot enable a cloud destination. */
internal object GitHubAutonomousEngineeringPolicy {
    fun enabled(profile: PlatformV2?): Boolean = profile?.enabled == true && profile.compatibleType.isLocalPlatform() && profile.isPrivateDestination()

    fun supportsSavedJobs(profile: PlatformV2?): Boolean = enabled(profile) && profile?.compatibleType in setOf(ClientType.LLAMA, ClientType.OLLAMA)

    fun instruction(profile: PlatformV2, workspace: GitHubConversationContext?): String =
        if (workspace?.mainProfileUid == profile.uid && enabled(profile)) {
            "\nAutonomous engineering is enabled for this local GitHub profile. For an authorized repository task, plan, inspect, implement, verify and independently review the work through completion or a concrete blocker. Prepare a working branch and draft PR when the task calls for code changes; watch CI asynchronously and repair failures within the accepted budgets. Preserve receipts and resume unfinished stages without repeating uncertain writes. Ask for consequential missing decisions. Starting a saved host job requires a verified engineering endpoint. Merge, release, deployment and administrative changes require the user's authorization; this mode never grants it."
        } else {
            ""
        }
}
