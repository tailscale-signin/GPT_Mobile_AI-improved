package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.CombinedDocumentDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalBlockIndex
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalManifest
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedSourceReference
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CombinedDocumentBlock(
    val sequence: Int,
    val start: Int,
    val text: String,
    val digest: String,
    val passages: List<CombinedPassageRecord>,
    val openingFence: String = "",
    val closingFence: String = "",
    val tableHeader: String = "",
    val attributionStatus: String = "RECORDED"
)

@Serializable
internal data class CombinedBlockAttribution(val passages: List<CombinedPassageRecord>, val openingFence: String = "", val closingFence: String = "", val tableHeader: String = "")

@Serializable
internal data class CombinedStoredFields(
    val thoughts: String,
    val timeline: List<AssistantTimelineItem>,
    val timelineText: Map<Int, String>,
    val sources: List<CombinedModelResponse>,
    val revisions: List<AssistantRevision>
)

data class StagedCombinedDocument(val original: MessageV2, val compact: MessageV2, val manifest: CombinedFinalManifest)

/** Durable body/source maps with explicit full reads and bounded visible-block reads. */
internal object CombinedDocuments {
    const val INLINE_CHARACTERS = 32_768
    private const val PREVIEW_CHARACTERS = 256
    private val recoveryReports = LinkedHashMap<String, CombinedQualityReport?>(2, 0.75f, true)

    fun needsDocument(message: MessageV2): Boolean = (message.currentRunId?.startsWith("combined-synthesis:") == true || message.combinedSources.isNotEmpty() || message.revisions.any { it.combinedManifest != null }) &&
        (
            message.combinedManifest != null ||
                message.content.length.toLong() + message.thoughts.length +
                message.combinedSources.sumOf { it.content.length.toLong() } + message.timeline.sumOf { it.content.length.toLong() } +
                message.revisions.sumOf { it.content.length.toLong() } > INLINE_CHARACTERS
            )

    suspend fun stage(dao: CombinedDocumentDao, message: MessageV2, run: AgentRun, completeness: String, force: Boolean = false): StagedCombinedDocument? {
        if (!force && !needsDocument(message)) return null
        return CombinedOperationTrace.measure("document_staging", CombinedOperationOwner(messageId = message.id, runId = run.runId)) {
            stageDocument(dao, message, run, completeness, force)
        }
    }

    private suspend fun stageDocument(dao: CombinedDocumentDao, message: MessageV2, run: AgentRun, completeness: String, force: Boolean): StagedCombinedDocument? = withContext(Dispatchers.Default) {
        if (!force && !needsDocument(message)) return@withContext null
        require(message.currentRunId == run.runId && message.id == run.assistantMessageId && message.ownershipFence == run.ownershipFence)
        val materialized = if (message.combinedManifest == null || message.content.length > PREVIEW_CHARACTERS || message.combinedManifest.materialized || (message.content.isEmpty() && message.combinedManifest.bodyLength != 0)) message else resolve(dao, message)
        val recorded = materialized.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
        val revision = recorded?.revisionId?.takeIf { it.isNotBlank() && !it.startsWith("recovered:") } ?: frozenRevision(run) ?: recorded?.revisionId?.takeIf(String::isNotBlank) ?: "pending:${run.runId}:${run.ownershipFence}"
        val report = recorded?.copy(revisionId = revision, answerDigest = recorded.answerDigest.ifBlank { researchHash(materialized.content) }) ?: CombinedAttributionRecovery.pending(materialized.content, revision)
        val source = when {
            recorded == null -> materialized.copy(timeline = materialized.timeline + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report))
            recorded != report -> materialized.copy(timeline = materialized.timeline.map { if (it.combinedQuality == recorded) it.copy(combinedQuality = report) else it })
            else -> materialized
        }
        require(report.answerDigest == researchHash(source.content)) { "COMBINED_MANIFEST_ANSWER_DIGEST" }
        require(report.passages.all { it.matches(source.content) && it.links.all { link -> link.matches(source.content) && link.start >= it.start && link.end <= it.end } }) { "COMBINED_MANIFEST_SOURCE_RANGE" }
        val revisionId = revision
        val storedCompleteness = when {
            recorded == null || report.status == CombinedAttributionRecovery.PENDING || report.status == "WRITING" && report.claimMapping == null -> CombinedAttributionRecovery.PENDING
            report.status == CombinedAttributionRecovery.RECOVERED -> CombinedAttributionRecovery.RECOVERED
            completeness == "COMPLETED" && !report.complete -> "PARTIAL"
            else -> completeness
        }
        val body = CombinedPayload.write(dao, run.runId, source.content)
        val textReferences = linkedMapOf<Int, String>()
        val metadataTimeline = source.timeline.mapIndexed { index, item ->
            if (item.type == AssistantTimelineItemType.TEXT && item.content.isNotEmpty()) {
                textReferences[index] = if (item.content == source.content) body.encode() else CombinedPayload.write(dao, run.runId, item.content).encode()
                item.copy(content = "")
            } else {
                item
            }
        }
        val archivedRevisions = source.revisions.map { revision ->
            if (revision.combinedManifest != null) {
                revision.copy(content = preview(revision.content), thoughts = "", timeline = lightTimeline(revision.timeline), combinedSources = storeSources(dao, revision.combinedManifest.runId, revision.combinedSources), combinedManifest = revision.combinedManifest.copy(materialized = false))
            } else if (revision.content.isNotEmpty() || revision.thoughts.isNotEmpty()) {
                val archived = source.copy(content = revision.content, thoughts = revision.thoughts, timeline = revision.timeline, combinedSources = revision.combinedSources, revisions = emptyList(), combinedManifest = null)
                val stored = requireNotNull(stage(dao, archived, run, "ARCHIVED", force = true))
                revision.copy(content = preview(revision.content), thoughts = "", timeline = lightTimeline(revision.timeline), combinedSources = stored.compact.combinedSources, combinedManifest = stored.manifest.reference())
            } else {
                revision
            }
        }
        val storedSources = storeSources(dao, run.runId, source.combinedSources)
        val fields = CombinedStoredFields(source.thoughts, metadataTimeline, textReferences, storedSources, archivedRevisions)
        val metadata = CombinedPayload.write(dao, run.runId, Json.encodeToString(fields))
        val provenanceDigest = researchHash(Json.encodeToString(report))
        val id = "combined-document:" + researchHash(listOf(run.runId, source.id.toString(), run.ownershipFence.toString(), revisionId, body.digest, metadata.digest, storedCompleteness, "document_format_v2").joinToString("\u0000"))
        val indexes = ArrayList<CombinedFinalBlockIndex>(body.count)
        for ((sequence, frame) in CombinedMarkdownPaging.frames(source.content).withIndex()) {
            val start = frame.start
            val text = source.content.substring(start, frame.end)
            val attribution = CombinedBlockAttribution(slicePassages(source.content, start, frame.end, report.passages), frame.openingFence, frame.closingFence, frame.tableHeader)
            val attributionReference = CombinedPayload.write(dao, run.runId, Json.encodeToString(attribution))
            val index = CombinedFinalBlockIndex(id, sequence, start, start + text.length, researchHash(text), attributionReference.encode(), "")
            indexes += index.copy(digest = indexDigest(index))
        }
        val manifest = CombinedFinalManifest(
            id, run.runId, source.id, revisionId, run.synthesisKey.orEmpty(), run.ownershipFence,
            body.encode(), metadata.encode(), researchHash(indexes.joinToString("\n") { it.digest }),
            body.length, body.count, body.digest, provenanceDigest, metadata.digest, storedCompleteness, 0
        )
        dao.insertCombinedFinalManifest(manifest)
        val retained = requireNotNull(dao.combinedFinalManifest(id))
        require(retained.copy(committedAt = 0) == manifest) { "COMBINED_MANIFEST_REWRITE" }
        indexes.chunked(CombinedPayload.PAGE_BATCH).forEach { batch ->
            dao.insertCombinedFinalBlockIndexes(batch)
            require(dao.combinedFinalBlockIndexPage(id, batch.first().sequence, batch.size) == batch) { "COMBINED_BLOCK_INDEX_REWRITE" }
        }
        val compact = source.copy(
            content = preview(source.content),
            thoughts = "",
            timeline = lightTimeline(source.timeline),
            combinedSources = storedSources,
            revisions = archivedRevisions,
            combinedManifest = manifest.reference()
        )
        StagedCombinedDocument(message, compact, retained)
    }

    private suspend fun storeSources(dao: CombinedDocumentDao, runId: String, sources: List<CombinedModelResponse>): List<CombinedModelResponse> =
        sources.map { original ->
            val content = if (original.originalDocument != null && original.content.isEmpty()) {
                val existing = original.originalDocument
                if (existing.runId == runId) return@map original
                CombinedPayload.read(dao, existing.runId, sourcePayload(existing).encode())
            } else {
                original.content
            }
            val payload = CombinedPayload.write(dao, runId, content)
            // Store each distinct synthetic frame once. The page index is small;
            // opening a late source page never scans earlier original text.
            val frames = linkedMapOf<String, Int>()
            val framePayloads = mutableListOf<String>()
            val frameIndexes = mutableListOf<Int>()
            for (frame in CombinedMarkdownPaging.frames(content)) {
                frameIndexes += if (frame.openingFence.isEmpty() && frame.closingFence.isEmpty() && frame.tableHeader.isEmpty()) {
                    -1
                } else {
                    val encoded = Json.encodeToString(CombinedBlockAttribution(emptyList(), frame.openingFence, frame.closingFence, frame.tableHeader))
                    frames[encoded] ?: frames.size.also { index ->
                        frames[encoded] = index
                        framePayloads += CombinedPayload.write(dao, runId, encoded).encode()
                    }
                }
            }
            original.copy(content = "", originalDocument = CombinedSourceReference(runId, payload.id, payload.count, payload.length, payload.digest, payload.storageVersion, payload.blocks, framePayloads, if (frames.isEmpty()) emptyList() else frameIndexes))
        }

    suspend fun commit(dao: CombinedDocumentDao, staged: StagedCombinedDocument, run: AgentRun, message: MessageV2): MessageV2 {
        require(staged.original == message && staged.manifest.runId == run.runId && staged.manifest.finalMessageId == message.id && staged.manifest.ownershipFence == run.ownershipFence) { "COMBINED_MANIFEST_OWNER_CHANGED" }
        val current = requireNotNull(dao.combinedFinalManifest(staged.manifest.id))
        require(current.copy(committedAt = 0) == staged.manifest.copy(committedAt = 0)) { "COMBINED_MANIFEST_CHANGED" }
        val committedAt = System.currentTimeMillis()
        if (current.committedAt == 0L) check(dao.markCombinedManifestCommitted(current.id, run.ownershipFence, committedAt) == 1)
        staged.compact.revisions.mapNotNull { it.combinedManifest }.forEach { reference ->
            val archived = requireNotNull(dao.combinedFinalManifest(reference.id))
            if (archived.committedAt == 0L) check(dao.markCombinedManifestCommitted(archived.id, archived.ownershipFence, committedAt) == 1)
        }
        return staged.compact
    }

    suspend fun blocks(dao: CombinedDocumentDao, reference: CombinedManifestReference, first: Int, count: Int): List<CombinedDocumentBlock> =
        CombinedOperationTrace.measure("document_block_read", CombinedOperationOwner(runId = reference.runId, revisionId = reference.revisionId, blockId = first.toString(), digest = reference.bodyDigest)) {
            readBlocks(dao, reference, first, count)
        }

    private suspend fun readBlocks(dao: CombinedDocumentDao, reference: CombinedManifestReference, first: Int, count: Int): List<CombinedDocumentBlock> = withContext(Dispatchers.Default) {
        require(count in 1..CombinedPayload.PAGE_BATCH)
        val manifest = checkedManifest(dao, reference)
        val recovered = recoveryReport(dao, manifest)
        val rows = CombinedPayload.page(dao, manifest.runId, CombinedPayload.reference(manifest.bodyPayload), first, count)
        val indexes = dao.combinedFinalBlockIndexPage(manifest.id, first, rows.size.coerceAtLeast(1))
        require(indexes.size == rows.size) { "COMBINED_BLOCK_INDEX_MISSING" }
        rows.zip(indexes).map { (row, index) ->
            require(index.sequence == row.sequence && index.start == row.start && index.end == row.start + row.text.length && index.textDigest == row.digest && index.digest == indexDigest(index)) { "COMBINED_BLOCK_INDEX_INTEGRITY" }
            val attribution = Json.decodeFromString<CombinedBlockAttribution>(CombinedPayload.read(dao, manifest.runId, index.attributionPayload))
            require(attribution.passages.all { it.matches(row.text) && it.links.all { link -> link.matches(row.text) && link.start >= it.start && link.end <= it.end } }) { "COMBINED_BLOCK_SOURCE_INTEGRITY" }
            val passages = recovered?.passages?.mapNotNull { passage ->
                // Clip against a bounded block without retaining the complete body in the cache.
                val start = maxOf(passage.start, row.start)
                val end = minOf(passage.end, row.start + row.text.length)
                if (start >= end) return@mapNotNull null
                val links = passage.links.mapNotNull { link ->
                    val from = maxOf(start, link.start)
                    val to = minOf(end, link.end)
                    if (from >= to) null else link.copy(start = from - row.start, end = to - row.start, digest = researchHash(row.text.substring(from - row.start, to - row.start)))
                }
                passage.copy(start = start - row.start, end = end - row.start, digest = researchHash(row.text.substring(start - row.start, end - row.start)), origin = "DURABLE_BLOCK_MAPPING_V1", links = links)
            } ?: attribution.passages
            CombinedDocumentBlock(row.sequence, row.start, row.text, row.digest, passages, attribution.openingFence, attribution.closingFence, attribution.tableHeader, recovered?.sourceLinkStatus ?: "RECORDED")
        }
    }

    suspend fun sourceBlocks(dao: CombinedDocumentDao, reference: CombinedManifestReference, source: CombinedModelResponse, first: Int, count: Int): List<CombinedDocumentBlock> =
        CombinedOperationTrace.measure("source_block_read", CombinedOperationOwner(runId = reference.runId, revisionId = reference.revisionId, blockId = first.toString(), digest = source.originalDocument?.digest, profileUid = source.platformUid)) {
            readSourceBlocks(dao, reference, source, first, count)
        }

    private suspend fun readSourceBlocks(dao: CombinedDocumentDao, reference: CombinedManifestReference, source: CombinedModelResponse, first: Int, count: Int): List<CombinedDocumentBlock> = withContext(Dispatchers.Default) {
        val manifest = checkedManifest(dao, reference)
        val owner = requireNotNull(dao.combinedDocumentOwner(manifest.finalMessageId)) { "COMBINED_SOURCE_OWNER_MISSING" }
        val expected = reference.copy(materialized = false)
        val retained = buildList {
            if (owner.combinedManifest?.copy(materialized = false) == expected) addAll(owner.combinedSources)
            owner.revisions.filter { it.combinedManifest?.copy(materialized = false) == expected }.forEach { addAll(it.combinedSources) }
        }
        require(source in retained) { "COMBINED_SOURCE_OWNER_CHANGED" }
        val document = requireNotNull(source.originalDocument) { "COMBINED_SOURCE_REFERENCE_MISSING" }
        require(document.runId == manifest.runId) { "COMBINED_SOURCE_RUN_CHANGED" }
        CombinedPayload.page(dao, document.runId, sourcePayload(document), first, count).map { block ->
            val frame = document.frameIndexes.getOrNull(block.sequence)?.takeIf { it >= 0 }?.let { index ->
                Json.decodeFromString<CombinedBlockAttribution>(CombinedPayload.read(dao, document.runId, document.framePayloads[index]))
            }
            require(frame?.passages.isNullOrEmpty()) { "COMBINED_SOURCE_FRAME_ATTRIBUTION" }
            CombinedDocumentBlock(block.sequence, block.start, block.text, block.digest, emptyList(), frame?.openingFence.orEmpty(), frame?.closingFence.orEmpty(), frame?.tableHeader.orEmpty())
        }
    }

    internal fun sourcePayload(reference: CombinedSourceReference): CombinedPayloadReference {
        require(reference.frameIndexes.isEmpty() && reference.framePayloads.isEmpty() || reference.frameIndexes.size == reference.blockCount && reference.frameIndexes.all { it == -1 || it in reference.framePayloads.indices }) { "COMBINED_SOURCE_FRAME_INDEX" }
        reference.framePayloads.forEach { CombinedPayload.reference(it) }
        return CombinedPayload.reference(CombinedPayloadReference(reference.payloadId, reference.blockCount, reference.length, reference.digest, reference.storageVersion, reference.blocks).encode())
    }

    suspend fun resolve(dao: CombinedDocumentDao, message: MessageV2): MessageV2 = withContext(Dispatchers.Default) {
        val reference = message.combinedManifest
        if (reference == null || reference.materialized) {
            val revisions = message.revisions.map { resolveRevision(dao, message, it) }
            val report = message.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
            val needsRecovery = message.currentRunId?.startsWith("combined-synthesis:") == true &&
                message.content.isNotBlank() &&
                CombinedAttributionRecovery.needed(report)
            val timeline = if (needsRecovery) {
                val recovered = CombinedAttributionRecovery.reconstruct(message.content, message.combinedSources, report?.revisionId?.takeIf(String::isNotBlank) ?: "recovered:${message.currentRunId}:${researchHash(message.content)}", report)
                message.timeline.filterNot { it.combinedQuality != null } + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = recovered)
            } else {
                message.timeline
            }
            return@withContext if (revisions == message.revisions && timeline == message.timeline) message else message.copy(revisions = revisions, timeline = timeline)
        }
        val manifest = checkedManifest(dao, reference)
        require(manifest.finalMessageId == message.id) { "COMBINED_MANIFEST_MESSAGE_IDENTITY" }
        val body = CombinedPayload.read(dao, manifest.runId, manifest.bodyPayload)
        val metadataText = CombinedPayload.read(dao, manifest.runId, manifest.metadataPayload)
        require(researchHash(metadataText) == manifest.metadataDigest) { "COMBINED_MANIFEST_METADATA" }
        val fields = Json.decodeFromString<CombinedStoredFields>(metadataText)
        val timeline = fields.timeline.mapIndexed { index, item ->
            fields.timelineText[index]?.let { item.copy(content = CombinedPayload.read(dao, manifest.runId, it)) } ?: item
        }
        require(researchHash(Json.encodeToString(timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality)) == manifest.provenanceDigest) { "COMBINED_MANIFEST_PROVENANCE" }
        val revisions = fields.revisions.map { resolveRevision(dao, message, it) }
        val sources = fields.sources.map { source ->
            source.originalDocument?.let { original ->
                require(original.runId == manifest.runId) { "COMBINED_SOURCE_RUN_CHANGED" }
                source.copy(content = CombinedPayload.read(dao, original.runId, sourcePayload(original).encode()), originalDocument = null)
            } ?: source
        }
        val report = timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
        val restoredTimeline = if (CombinedAttributionRecovery.needed(report)) {
            val recovered = CombinedAttributionRecovery.reconstruct(body, sources, report?.revisionId?.takeIf(String::isNotBlank) ?: "recovered:${manifest.runId}:${manifest.bodyDigest}", report)
            cacheRecoveryReport(manifest.id, recovered)
            timeline.filterNot { it.combinedQuality != null } + AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = recovered)
        } else {
            timeline
        }
        message.copy(content = body, thoughts = fields.thoughts, timeline = restoredTimeline, combinedSources = sources, revisions = revisions, combinedManifest = reference.copy(materialized = true))
    }

    private fun frozenRevision(run: AgentRun): String? {
        val frozen = run.synthesisSnapshot ?: return null
        if (researchHash(frozen) != run.synthesisKey) return null
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(frozen).liveRevisionId(run.runId) }.getOrNull()
    }

    private suspend fun recoveryReport(dao: CombinedDocumentDao, manifest: CombinedFinalManifest): CombinedQualityReport? {
        synchronized(recoveryReports) { if (recoveryReports.containsKey(manifest.id)) return recoveryReports[manifest.id] }
        if (manifest.completeness !in setOf(CombinedAttributionRecovery.PENDING, "WRITING") && manifest.provenanceDigest != researchHash("null")) return null
        val metadata = CombinedPayload.read(dao, manifest.runId, manifest.metadataPayload)
        require(researchHash(metadata) == manifest.metadataDigest) { "COMBINED_MANIFEST_METADATA" }
        val fields = Json.decodeFromString<CombinedStoredFields>(metadata)
        require(researchHash(Json.encodeToString(fields.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality)) == manifest.provenanceDigest) { "COMBINED_MANIFEST_PROVENANCE" }
        val recorded = fields.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
        if (!CombinedAttributionRecovery.needed(recorded)) {
            cacheRecoveryReport(manifest.id, null)
            return null
        }
        val sources = fields.sources.map { source ->
            source.originalDocument?.let { original ->
                require(original.runId == manifest.runId) { "COMBINED_SOURCE_RUN_CHANGED" }
                source.copy(content = CombinedPayload.read(dao, original.runId, sourcePayload(original).encode()), originalDocument = null)
            } ?: source
        }
        val body = CombinedPayload.read(dao, manifest.runId, manifest.bodyPayload)
        val revision = fields.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality?.revisionId?.takeIf(String::isNotBlank) ?: "recovered:${manifest.runId}:${manifest.bodyDigest}"
        val report = CombinedAttributionRecovery.reconstruct(body, sources, revision, recorded)
        cacheRecoveryReport(manifest.id, report)
        return report
    }

    private fun cacheRecoveryReport(manifestId: String, report: CombinedQualityReport?) {
        synchronized(recoveryReports) {
            recoveryReports[manifestId] = report?.copy(claimMapping = null)
            while (recoveryReports.size > 2) recoveryReports.remove(recoveryReports.keys.first())
        }
    }

    private suspend fun resolveRevision(dao: CombinedDocumentDao, message: MessageV2, revision: AssistantRevision): AssistantRevision {
        if (revision.combinedManifest == null) return revision
        val resolved = resolve(dao, message.copy(content = revision.content, thoughts = revision.thoughts, timeline = revision.timeline, combinedSources = revision.combinedSources, revisions = emptyList(), combinedManifest = revision.combinedManifest))
        return revision.copy(content = resolved.content, thoughts = resolved.thoughts, timeline = resolved.timeline, combinedSources = resolved.combinedSources, combinedManifest = resolved.combinedManifest)
    }

    suspend fun portable(dao: CombinedDocumentDao, message: MessageV2): MessageV2 = inlinePortable(resolve(dao, message))

    fun inlinePortable(message: MessageV2): MessageV2 {
        message.combinedManifest?.let { require(message.content.length == it.bodyLength && researchHash(message.content) == it.bodyDigest) { "Export is missing the complete saved answer." } }
        message.revisions.forEach { revision -> revision.combinedManifest?.let { require(revision.content.length == it.bodyLength && researchHash(revision.content) == it.bodyDigest) { "Export is missing a complete saved revision." } } }
        fun portableSources(sources: List<CombinedModelResponse>): List<CombinedModelResponse> = sources.map { source ->
            source.originalDocument?.let { require(source.content.length == it.length && researchHash(source.content) == it.digest) { "Export is missing a complete original contribution." } }
            source.copy(originalDocument = null)
        }
        return message.copy(combinedManifest = null, combinedSources = portableSources(message.combinedSources), revisions = message.revisions.map { it.copy(combinedManifest = null, combinedSources = portableSources(it.combinedSources)) })
    }

    private suspend fun checkedManifest(dao: CombinedDocumentDao, reference: CombinedManifestReference): CombinedFinalManifest {
        val manifest = requireNotNull(dao.combinedFinalManifest(reference.id)) { "COMBINED_MANIFEST_MISSING" }
        require(manifest.reference() == reference.copy(materialized = false)) { "COMBINED_MANIFEST_REFERENCE" }
        val payload = CombinedPayload.reference(manifest.bodyPayload)
        require(payload.length == reference.bodyLength && payload.count == reference.blockCount && payload.digest == reference.bodyDigest) { "COMBINED_MANIFEST_BODY" }
        return manifest
    }

    internal fun slicePassages(answer: String, start: Int, end: Int, passages: List<CombinedPassageRecord>): List<CombinedPassageRecord> = passages.mapNotNull { passage ->
        val clippedStart = maxOf(start, passage.start)
        val clippedEnd = minOf(end, passage.end)
        if (clippedStart >= clippedEnd) return@mapNotNull null
        val links = passage.links.mapNotNull { link ->
            val linkStart = maxOf(clippedStart, link.start)
            val linkEnd = minOf(clippedEnd, link.end)
            if (linkStart >= linkEnd) null else link.copy(start = linkStart - start, end = linkEnd - start, digest = researchHash(answer.substring(linkStart, linkEnd)))
        }
        passage.copy(
            start = clippedStart - start,
            end = clippedEnd - start,
            digest = researchHash(answer.substring(clippedStart, clippedEnd)),
            origin = if (passage.origin.startsWith("LOCAL_CLAIM_MAPPING_V")) "DURABLE_BLOCK_MAPPING_V1" else passage.origin,
            links = links
        )
    }

    private fun indexDigest(index: CombinedFinalBlockIndex): String = researchHash(listOf(index.sequence.toString(), index.start.toString(), index.end.toString(), index.textDigest, index.attributionPayload).joinToString("\u0000"))

    private fun preview(text: String): String {
        var end = minOf(PREVIEW_CHARACTERS, text.length)
        if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    private fun lightTimeline(timeline: List<AssistantTimelineItem>): List<AssistantTimelineItem> = timeline.map { item ->
        val quality = item.combinedQuality
        item.copy(
            content = if (item.type == AssistantTimelineItemType.TEXT) "" else item.content,
            combinedQuality = quality?.copy(passages = emptyList(), decisions = emptyList(), verificationReceipts = emptyList(), supportEdges = emptyList(), checks = emptyList(), verificationNeeds = emptyList(), sourceDispositions = emptyList())
        )
    }
}
