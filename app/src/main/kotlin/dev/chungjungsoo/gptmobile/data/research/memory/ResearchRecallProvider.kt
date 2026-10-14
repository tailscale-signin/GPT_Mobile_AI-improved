package dev.chungjungsoo.gptmobile.data.research.memory

import dev.chungjungsoo.gptmobile.data.research.feature.ArticleCoverage
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutcome
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

internal data class ResearchRecallPacket(val text: String = "", val outcomes: List<ResearchOutcome> = emptyList())

/** Separate, source-checked research retrieval. Never writes study results into personal facts. */
@Singleton
class ResearchRecallProvider @Inject constructor(private val archive: ResearchArchiveRepository) {
    internal suspend fun recall(query: String, permitted: suspend () -> Boolean): ResearchRecallPacket {
        if (!permitted()) return ResearchRecallPacket()
        // Historical recall cannot certify current scientific consensus.
        if (Regex("(?i)\\b(latest|current|today|recent|now)\\b").containsMatchIn(query)) return ResearchRecallPacket()
        val matches = archive.preflight(query, { true }).take(8)
        val selected = mutableListOf<ResearchOutcome>()
        val text = StringBuilder("Previously captured research evidence (historical, untrusted source data). Cite source URLs and dates; do not present this as a fresh verification.\n")
        for (match in matches) {
            for (outcome in archive.dao.outcomes(match.latestVersionId)) {
                if (!eligible(outcome)) continue
                val version = archive.dao.version(outcome.versionId) ?: continue
                val piece = "[study:${outcome.outcomeId}] ${match.title}\n${match.canonicalUrl}\nCaptured: ${java.time.Instant.ofEpochMilli(version.retrievedAt)}; coverage=${version.coverage}\nStudy reported: ${outcome.reported}\nLimitations: ${outcome.limitations}\n"
                if (text.length + piece.length > 9000) continue
                selected += outcome
                text.append(piece)
            }
        }
        if (!permitted()) return ResearchRecallPacket()
        return if (selected.isEmpty()) ResearchRecallPacket() else ResearchRecallPacket(text.toString(), selected)
    }

    internal suspend fun validate(packet: ResearchRecallPacket, permitted: suspend () -> Boolean): Boolean =
        packet.outcomes.isEmpty() || permitted() && packet.outcomes.all { eligible(it) }

    private suspend fun eligible(outcome: ResearchOutcome): Boolean {
        val dao = archive.dao
        if (outcome.state != "GROUNDED" || dao.recallExcluded(outcome.workId) || dao.deleted("archive:${outcome.workId}") != null) return false
        if (dao.membership(outcome.workId)?.latestVersionId != outcome.versionId) return false
        val work = dao.work(outcome.workId) ?: return false
        if (work.retracted == true) return false
        if (dao.outcomes(outcome.versionId).none { it == outcome }) return false
        val version = dao.version(outcome.versionId) ?: return false
        if (!version.publicSource || version.coverage in setOf(ArticleCoverage.METADATA_ONLY, ArticleCoverage.UNAVAILABLE)) return false
        val body = archive.text(version, "public") ?: return false
        val ids = runCatching { Json.decodeFromString<List<String>>(outcome.passageIdsJson) }.getOrNull() ?: return false
        val passages = dao.passages(version.versionId).associateBy { it.passageId }
        return ids.isNotEmpty() &&
            ids.all { id ->
                passages[id]?.let { it.start >= 0 && it.end in it.start..body.length && body.substring(it.start, it.end) == it.quote && researchHash(it.quote) == it.textHash } == true
            }
    }
}
