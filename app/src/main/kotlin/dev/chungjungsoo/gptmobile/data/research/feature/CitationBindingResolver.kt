package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class CitationOccurrence(val key: String, val paragraphId: String)

/** Only explicit strong identifiers or a registered turn citation can create an article binding. */
internal fun articleCitationOccurrences(text: String): List<CitationOccurrence> {
    if (text.length > 1_000_000) return emptyList()
    val withoutCode = text.replace(Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)")) { " ".repeat(it.value.length) }
        .replace(Regex("`[^`\n]*`")) { " ".repeat(it.value.length) }
    val keys = Regex("\\[(S[0-9a-f]{1,32})\\]|https?://[^\\s<>()\\[\\]]+|(?i:doi:\\s*)(10\\.[0-9]{4,9}/[^\\s<>()]+)|(?i:PMID:\\s*)([0-9]{1,12})|(?i:PMCID:\\s*)(PMC[0-9]+)|(?i:arxiv:\\s*)((?:[0-9]{4}\\.[0-9]{4,5}|[a-z-]+/[0-9]{7})(?:v[0-9]+)?)")
    return withoutCode.split(Regex("\\n\\s*\\n")).flatMapIndexed { index, paragraph ->
        val paragraphId = "$index:${researchHash(paragraph.trim())}"
        keys.findAll(paragraph).mapNotNull { match ->
            val raw = match.value.trimEnd('.', ',', ';', ':')
            val key = when {
                match.groups[1] != null -> match.groupValues[1]
                match.groups[2] != null -> "doi:${SourceIdentity.normalizeDoi(match.groupValues[2])}"
                match.groups[3] != null -> "pmid:${match.groupValues[3]}"
                match.groups[4] != null -> "pmcid:${match.groupValues[4].uppercase()}"
                match.groups[5] != null -> "arxiv:${match.groupValues[5].lowercase()}"
                raw.startsWith("http") -> {
                    val doi = SourceIdentity.normalizeDoi(raw)
                    if (doi.isNotBlank()) "doi:$doi" else "url:${SourceIdentity.canonicalResearchUrl(raw)}"
                }
                else -> null
            }
            key?.takeIf { it.length <= 2000 }?.let { CitationOccurrence(it, paragraphId) }
        }.toList()
    }.distinct().take(120)
}

@Singleton
class CitationBindingResolver @Inject constructor(private val archive: ResearchArchiveRepository, private val sessions: ResearchSessionStore) {
    suspend fun bind(message: MessageV2): List<MessageEvidenceBinding> {
        require(message.id > 0 && message.chatId > 0)
        val text = message.effectiveContent()
        val revision = researchHash("${message.effectiveRunId()}|${message.activeRevisionIndex}|$text")
        val saved = archive.dao.bindings(message.id.toString(), revision)
        if (saved.isNotEmpty()) return saved
        if (archive.dao.deleted("chat:${message.chatId}") != null) return emptyList()
        val runs = sessions.exportChat(message.chatId).filter { it.runId == message.effectiveRunId() || it.turnId in setOf("${message.chatId}:${message.linkedMessageId}", message.linkedMessageId.toString()) }
        val registry = runs.flatMap { run -> run.snapshot.sources.map { Triple(it, run.authority, run.runId) } }
        val occurrences = articleCitationOccurrences(text)
        return archive.database.withTransaction {
            check(archive.dao.deleted("chat:${message.chatId}") == null)
            occurrences.forEach { occurrence ->
                val candidates = registry.filter { (source, _, _) -> sourceCitationKeys(source).contains(occurrence.key) }
                // Duplicate S1 namespaces or conflicting versions cannot be resolved by title or recency.
                val resolved = candidates.mapNotNull { (source, authority, _) ->
                    val aliases = sourceCitationKeys(source).filterNot { it.matches(Regex("S[0-9a-f]+")) }
                    val scopedIds = aliases.mapNotNull { alias -> archive.dao.aliasWork("${researchHash(authority)}|$alias") }.distinct()
                    val workIds = scopedIds.ifEmpty { aliases.mapNotNull { alias -> archive.dao.aliasWork("public|$alias") }.distinct() }
                    if (workIds.size != 1) return@mapNotNull null
                    val versions = archive.dao.versions(workIds.single()).filter { it.sourceVersion == source.versionId && (it.publicSource || it.authority == authority) }
                    val version = versions.singleOrNull { candidate -> source.bodyChunks.isNotEmpty() && candidate.bodyChunksJson == Json.encodeToString(source.bodyChunks) }
                        ?: versions.singleOrNull() ?: return@mapNotNull null
                    Triple(source, version, authority)
                }.distinctBy { it.second.versionId }
                val match = resolved.singleOrNull() ?: return@forEach
                val (source, version, authority) = match
                val snapshot = buildJsonObject {
                    put("schemaVersion", 1)
                    put("title", source.title)
                    put("canonicalUrl", source.canonicalUrl)
                    put("coverage", version.coverage.name)
                    put("contentHash", version.contentHash)
                    put("publicationType", source.publicationType)
                    put("peerReview", source.reviewStatus)
                    put("independentReview", "not_established")
                    put("retrievedAt", source.retrievedAt)
                }
                val binding = MessageEvidenceBinding(message.id.toString(), revision, "${occurrence.key}|${occurrence.paragraphId}", message.chatId, version.workId, version.versionId, occurrence.paragraphId, snapshot.toString(), authority)
                archive.dao.binding(binding)
                archive.dao.root(BodyReference("citation", "${message.chatId}:${message.id}:$revision", version.versionId))
            }
            archive.dao.bindings(message.id.toString(), revision)
        }
    }
    private fun sourceCitationKeys(source: ResearchSource): Set<String> = buildSet {
        add(source.id)
        add("url:${source.canonicalUrl}")
        SourceIdentity.normalizeDoi(source.doi).takeIf { it.isNotBlank() }?.let { add("doi:$it") }
        source.pmid.takeIf { it.isNotBlank() }?.let { add("pmid:$it") }
        source.pmcid.takeIf { it.isNotBlank() }?.let { add("pmcid:${it.uppercase()}") }
        if (source.paperId.startsWith("arxiv:")) add(source.paperId.lowercase())
    }
}
