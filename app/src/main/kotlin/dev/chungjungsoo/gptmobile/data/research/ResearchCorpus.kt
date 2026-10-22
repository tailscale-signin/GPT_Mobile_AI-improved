package dev.chungjungsoo.gptmobile.data.research

/** Frozen, bounded evidence passed to every Combined analyst in a single logical turn. */
data class ResearchCorpus(
    val snapshotVersion: Int,
    val sources: List<ResearchSource>,
    val claims: List<ResearchClaim>,
    val toolEvents: List<ResearchToolEvent>,
    val task: String = "",
    val complete: Boolean = false,
    val updatedAt: Long = 0,
    val phase: String = "Partial coverage",
    val cancellationOrigin: String = "",
    val notes: List<String> = emptyList(),
    val questions: List<ResearchQuestion> = emptyList(),
    val taskId: String = "",
    val rootWorkId: String = "",
    val originatingUserMessageId: Int = 0,
    val taskChatId: Int = 0,
    val numericalFactBindingRequired: Boolean = false
) {
    fun json() = dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket.build(
        ResearchSnapshot(task = task, sources = sources, claims = claims, toolEvents = toolEvents, complete = complete, updatedAt = updatedAt, phase = phase, cancellationOrigin = cancellationOrigin, notes = notes, questionPlan = questions, taskId = taskId, rootWorkId = rootWorkId, originatingUserMessageId = originatingUserMessageId, taskChatId = taskChatId, numericalFactBindingRequired = numericalFactBindingRequired)
    ).json()

    /** Resolve only URLs explicitly present in a contribution; never guess from local S1 labels. */
    fun citedSources(text: String): List<ResearchSource> {
        val citedUrls = Regex("https?://[^\\s<>\\[\\]{}]+", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', ')') }
            .mapNotNull { url -> runCatching { SourceIdentity.canonicalResearchUrl(url) }.getOrNull()?.takeIf(String::isNotBlank) }
            .toSet()
        if (citedUrls.isEmpty()) return emptyList()
        return sources.filter { source ->
            runCatching { SourceIdentity.canonicalResearchUrl(source.canonicalUrl.ifBlank { source.url }) }.getOrNull() in citedUrls
        }.distinctBy { it.id }
    }

    companion object {
        private const val MAX_EVENTS = 200

        /** Callers supply sessions from one accepted turn and authority partition. */
        fun freezeAll(snapshots: List<ResearchSnapshot>): ResearchCorpus? {
            if (snapshots.isEmpty()) return null
            require(snapshots.map { it.taskId }.filter(String::isNotBlank).distinct().size <= 1) { "RESEARCH_CORPUS_TASK_MISMATCH" }
            val ordered = snapshots.sortedBy { it.updatedAt }
            return freeze(mergeResearchSnapshots(ordered.dropLast(1), ordered.last()))
        }

        fun freeze(snapshot: ResearchSnapshot): ResearchCorpus {
            val sources = snapshot.sources
            val validClaims = snapshot.claims.mapNotNull { claim ->
                val valid = validatedResearchClaims(listOf(claim.json()), sources, bindNewCapture = false).singleOrNull() ?: return@mapNotNull null
                claim.takeIf { it.verdict in setOf("Supported", "Contradicted", "Insufficient", "Unreviewed") } ?: valid
            }
            return ResearchCorpus(2, sources.toList(), validClaims.toList(), snapshot.toolEvents.takeLast(MAX_EVENTS).toList(), snapshot.task, snapshot.complete, snapshot.updatedAt, snapshot.phase, snapshot.cancellationOrigin, snapshot.notes.toList(), snapshot.questionPlan.toList(), snapshot.taskId, snapshot.rootWorkId, snapshot.originatingUserMessageId, snapshot.taskChatId, snapshot.numericalFactBindingRequired)
        }
    }
}
