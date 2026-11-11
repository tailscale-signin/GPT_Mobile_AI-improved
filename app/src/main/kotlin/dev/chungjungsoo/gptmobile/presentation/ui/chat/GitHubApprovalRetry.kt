package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GitHubApprovalBinding

/** This is a UI action guard, not permission: resolved cards still allow saved-state reconciliation. */
internal fun gitHubApprovalRetryBlock(failure: String?, pendingCard: Boolean): String? =
    if (pendingCard && GitHubApprovalBinding.isApprovalPause(failure)) {
        "The existing approval card is waiting for your decision. Approve & Commit, Reject, or Keep fixture there; generic Continue and Retry are paused."
    } else {
        null
    }
