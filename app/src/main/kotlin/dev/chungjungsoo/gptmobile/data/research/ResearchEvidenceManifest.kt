package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Small append-only index; an entry is a retained receipt, never a permission grant. */
@Serializable
data class ResearchEvidenceManifest(
    val version: Int = 1,
    val taskId: String,
    val rootWorkId: String,
    val chatId: Int,
    val originatingUserMessageId: Int,
    val originalRequestDigest: String,
    val revisions: List<ResearchEvidenceRevision> = emptyList(),
    val partialCheckpointIds: Set<String> = emptySet(),
    val deliveryReceiptIds: Set<String> = emptySet()
)

@Serializable
data class ResearchEvidenceRevision(
    val id: String,
    val runId: String,
    val authority: String,
    val generation: Long,
    val evidenceRevision: String,
    val phase: String,
    val readableSources: Int,
    val reviewedClaims: Int,
    val totalSources: Int,
    val updatedAt: Long
)

@Serializable
internal data class RetainedResearchEvidence(
    val taskId: String,
    val rootWorkId: String,
    val chatId: Int,
    val originalRequestDigest: String,
    val runId: String,
    val authority: String,
    val generation: Long,
    val snapshot: JsonObject
)

/** Callers first isolate the accepted turn and authorized partition. A newer empty shell cannot erase work. */
internal fun richestResearchSnapshot(snapshots: List<ResearchSnapshot>): ResearchSnapshot? {
    if (snapshots.isEmpty()) return null
    require(snapshots.map { it.task }.distinct().size == 1) { "RESEARCH_MANIFEST_TASK_CHANGED" }
    val latest = snapshots.maxBy { it.updatedAt }
    // A pending provider operation owns an exact source/claim cursor. Retained revision
    // recovery must not combine it with another evidence version before it is accepted.
    if (latest.deferredStage != null) return latest
    val best = snapshots.maxWith(
        compareBy<ResearchSnapshot> { it.sources.count(ResearchSource::readable) }
            .thenBy { it.claims.count { claim -> claim.exactQuoteVerified && claim.verdict != "Unreviewed" } }
            .thenBy { it.sources.size }.thenBy { it.claims.size }.thenBy { it.updatedAt }
    )
    // Merging an exact subset would unnecessarily rename question IDs and stale an otherwise
    // current review digest. Preserve the strongest immutable snapshot verbatim in that case.
    if (snapshots.all { other ->
            best.sources.containsAll(other.sources) &&
                best.claims.containsAll(other.claims) &&
                best.questionPlan.containsAll(other.questionPlan) &&
                best.questions.containsAll(other.questions) &&
                best.queries.containsAll(other.queries) &&
                best.toolEvents.containsAll(other.toolEvents) &&
                (other.currentReviewRecord() == null || other.currentReviewRecord() == best.currentReviewRecord())
        }
    ) {
        // Sparse checkpoints can still record work spent after this capture. Keep
        // the exact evidence/review while carrying their monotonic counters.
        val retained = best.copy(
            round = snapshots.maxOf { it.round },
            attempts = snapshots.maxOf { it.attempts },
            searches = snapshots.maxOf { it.searches },
            engineResponses = snapshots.maxOf { it.engineResponses },
            engineFailures = snapshots.maxOf { it.engineFailures },
            targetSources = snapshots.maxOf { it.targetSources },
            numericalFactBindingRequired = snapshots.any { it.numericalFactBindingRequired },
            deferredStage = null,
            extractedSourceVersions = snapshots.flatMap { it.extractedSourceVersions }.toSet()
        )
        return if (retained == best) best else retained
    }
    return mergeResearchSnapshots(snapshots.filterNot { it === best }, best).copy(deferredStage = null)
}
