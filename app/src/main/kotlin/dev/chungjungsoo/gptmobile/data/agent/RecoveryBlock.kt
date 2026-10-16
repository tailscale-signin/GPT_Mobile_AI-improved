package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable notice metadata. No token, raw evidence or endpoint credentials are retained here. */
@Serializable
data class RecoveryBlock(
    val code: String = "SOURCE_PERMISSION_REVOKED",
    val stage: String = "saved_evidence_dispatch",
    val rootWorkId: String,
    val generationId: String,
    val destinationIdentity: String,
    val policyRevision: String,
    val evidenceRevision: String,
    val affectedEvidenceIds: List<String>,
    val allowedActions: List<String> = listOf("REVIEW_ACCESS", "RETRY_AFTER_AUTHORIZED_REVISION", "START_WITHOUT_SAVED_EVIDENCE"),
    val message: String = RECOVERY_ACCESS_MESSAGE,
    val fingerprint: String,
    val attemptCount: Int = 1,
    val lastAction: String = "CONTINUE_RUN"
)

internal fun recoveryDestinationIdentity(profile: PlatformV2): String = researchHash(Json.encodeToString(profile.copy(name = "", labels = "", isFavorite = false)))

internal fun recoveryBlock(root: String, generation: String, destination: String, policy: String, evidence: List<String>, messages: List<MessageV2>, action: String): RecoveryBlock {
    val ids = evidence.distinct().sorted()
    val revision = researchHash(ids.joinToString("\n"))
    val fingerprint = researchHash("SOURCE_PERMISSION_REVOKED|saved_evidence_dispatch|$root|$destination|$policy|$revision")
    val previous = messages.flatMap { message -> message.timeline + message.revisions.flatMap { it.timeline } }.mapNotNull { it.recoveryBlock }.filter { it.fingerprint == fingerprint }
    previous.firstOrNull { it.generationId == generation }?.let { return it }
    return RecoveryBlock(rootWorkId = root, generationId = generation, destinationIdentity = destination, policyRevision = policy, evidenceRevision = revision, affectedEvidenceIds = ids, fingerprint = fingerprint, attemptCount = (previous.maxOfOrNull { it.attemptCount } ?: 0).coerceAtMost(Int.MAX_VALUE - 1) + 1, lastAction = action)
}

/** Expected physical-boundary denial; adapters terminate the round without invoking native/provider work. */
internal class SavedEvidenceAccessDenied : Exception("SOURCE_PERMISSION_REVOKED: $RECOVERY_ACCESS_MESSAGE")
