package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord

/** This is a UI action guard, not permission: resolved cards still allow saved-state reconciliation. */
internal fun gitHubApprovalRetryBlock(failure: String?, pendingCard: Boolean): String? =
    if (pendingCard && failure != null && ProfileFailureRecord.classify(failure).category == "WAITING_FOR_APPROVAL") {
        "Review the proposed changes card above to approve or reject the saved patch. Retrying does not change its approval."
    } else {
        null
    }
