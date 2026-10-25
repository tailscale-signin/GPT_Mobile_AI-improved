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

    /**
     * A single-pass Combined turn dispatches synthesis to its accepted primary even when
     * that profile failed collection. Its absence as a source must not silently suppress
     * synthesis; all writer UIDs still have to belong to the accepted, enabled roster.
     */
    fun synthesisDispatchOrder(
        sources: List<CombinedModelResponse>,
        acceptedProfileUids: List<String>,
        preferredUid: String?,
        eligibleProfileUids: Set<String>,
        singlePass: Boolean
    ): List<String> {
        val contributors = eligibleOrder(sources, acceptedProfileUids, preferredUid, eligibleProfileUids)
        if (
            !singlePass ||
            preferredUid == null ||
            preferredUid !in acceptedProfileUids ||
            preferredUid !in eligibleProfileUids
        ) {
            return contributors
        }
        return (listOf(preferredUid) + contributors.filterNot { it == preferredUid }).distinct()
    }
}
