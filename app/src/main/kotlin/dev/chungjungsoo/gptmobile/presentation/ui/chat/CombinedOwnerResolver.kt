package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** Selects among the accepted, usable contributors; preference is never permission to add a model. */
internal object CombinedOwnerResolver {
    fun eligibleOrder(
        sources: List<CombinedModelResponse>,
        acceptedProfileUids: List<String>,
        preferredUid: String?,
        eligibleProfileUids: Set<String>
    ): List<String> {
        val usable = sources.filter {
            it.platformUid in acceptedProfileUids &&
                it.platformUid in eligibleProfileUids &&
                it.content.isNotBlank() &&
                CombinedContributionUsability.assess(it.completionStatus, it.content, allowPartial = true).admitted
        }.mapTo(hashSetOf()) { it.platformUid }
        return usable.sortedWith(compareBy<String> { it != preferredUid }.thenBy { it })
    }
}
