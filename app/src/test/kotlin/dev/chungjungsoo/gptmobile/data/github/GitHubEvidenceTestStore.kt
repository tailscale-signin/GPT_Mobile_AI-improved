package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject

/** The current verification is a full snapshot; append-only events are compact history. */
internal fun Map<String, JsonObject>.currentGitHubVerificationOrNull(): JsonObject? = entries
    .singleOrNull { it.key.startsWith("verification_current_") && GitHubEvidenceBook.currentSnapshot(it.key) }?.value

internal fun Map<String, JsonObject>.currentGitHubVerification(): JsonObject = requireNotNull(currentGitHubVerificationOrNull())
