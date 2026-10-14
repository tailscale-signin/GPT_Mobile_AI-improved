package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ContributionAvailability { RETAINED, PARTIAL, MISSING, INVALID }

@Serializable
data class ContributionRevision(
    val profileUid: String,
    val bodyDigest: String?,
    val availability: ContributionAvailability,
    val localOnly: Boolean,
    val eligibleForComparison: Boolean
)

@Serializable
data class ContributionSpan(
    val id: String,
    val profileUid: String,
    val bodyDigest: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val startUtf8: Long,
    val endUtf8: Long,
    val spanDigest: String
)

/** Origin occurrences are preserved. Reading a shared artifact does not transfer its ownership. */
@Serializable
data class ContributionLineage(
    val version: Int = 1,
    val corpusDigest: String,
    val revisions: List<ContributionRevision>,
    val spans: List<ContributionSpan>,
    val identicalOccurrences: Map<String, List<String>>,
    val comparisonComplete: Boolean = false,
    val semanticSupportVerified: Boolean = false
) {
    fun resolve(spanId: String, sources: List<CombinedModelResponse>, allowLocalOnly: Boolean): String? {
        val span = spans.singleOrNull { it.id == spanId } ?: return null
        val revision = revisions.singleOrNull { it.profileUid == span.profileUid } ?: return null
        if (!revision.eligibleForComparison || revision.localOnly && !allowLocalOnly) return null
        val source = sources.singleOrNull { it.platformUid == span.profileUid } ?: return null
        if (source.localOnly && !allowLocalOnly || researchHash(source.content) != span.bodyDigest) return null
        if (span.startUtf16 !in 0..source.content.length || span.endUtf16 !in span.startUtf16..source.content.length) return null
        val text = source.content.substring(span.startUtf16, span.endUtf16)
        return text.takeIf { researchHash(it) == span.spanDigest }
    }

    companion object {
        fun freeze(selectedProfileUids: List<String>, sources: List<CombinedModelResponse>): ContributionLineage {
            val revisions = selectedProfileUids.distinct().map { uid ->
                val candidates = sources.filter { it.platformUid == uid }
                val source = candidates.singleOrNull()
                val availability = when {
                    candidates.isEmpty() -> ContributionAvailability.MISSING
                    source == null || source.content.isBlank() -> ContributionAvailability.INVALID
                    source.completionStatus == "COMPLETED" -> ContributionAvailability.RETAINED
                    else -> ContributionAvailability.PARTIAL
                }
                ContributionRevision(uid, source?.let { researchHash(it.content) }, availability, source?.localOnly == true, availability == ContributionAvailability.RETAINED)
            }
            val spans = buildList {
                for (revision in revisions.filter { it.eligibleForComparison }) {
                    val source = sources.single { it.platformUid == revision.profileUid }
                    var start = 0
                    var bytes = 0L
                    while (start < source.content.length) {
                        val newline = source.content.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: source.content.length
                        var end = minOf(newline, start + 1600)
                        if (end < source.content.length && Character.isHighSurrogate(source.content[end - 1])) end--
                        val text = source.content.substring(start, end)
                        val nextBytes = bytes + text.toByteArray(Charsets.UTF_8).size
                        if (text.isNotBlank()) {
                            add(ContributionSpan(researchHash("${revision.profileUid}|${revision.bodyDigest}|$start|$end"), revision.profileUid, requireNotNull(revision.bodyDigest), start, end, bytes, nextBytes, researchHash(text)))
                        }
                        start = end
                        bytes = nextBytes
                    }
                }
            }
            val identity = Json.encodeToString(revisions) + Json.encodeToString(spans)
            return ContributionLineage(
                corpusDigest = researchHash(identity),
                revisions = revisions,
                spans = spans,
                identicalOccurrences = spans.groupBy { it.spanDigest }.filterValues { it.size > 1 }.mapValues { (_, occurrences) -> occurrences.map { it.id } }
            )
        }
    }
}
