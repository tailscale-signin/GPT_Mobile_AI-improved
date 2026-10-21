package dev.chungjungsoo.gptmobile.data.research

/** Merge only already-authorized partitions, binding each claim to its own captured source version. */
internal fun mergeResearchSnapshots(previous: List<ResearchSnapshot>, incoming: ResearchSnapshot): ResearchSnapshot {
    val snapshots = if (isResearchConversationOnly(incoming.task)) listOf(incoming) else previous + incoming
    fun identity(source: ResearchSource) = source.equivalenceGroup.ifBlank { source.canonicalUrl.ifBlank { source.url } } + ":" + source.versionId.ifBlank { researchHash(source.passage) }
    val groups = snapshots.flatMap { it.sources }.groupBy(::identity)
    val identitiesPerId = snapshots.flatMap { it.sources }.groupBy { it.id }.mapValues { (_, values) -> values.map(::identity).distinct().size }
    val sourcesByIdentity = groups.mapValues { (key, versions) ->
        val best = versions.maxWith(compareBy<ResearchSource> { it.readable }.thenBy { it.retrievalQuality == "FULL_TEXT" }.thenBy { it.status == "Read" }.thenBy { it.bodyChunks.size }.thenBy { it.passage.length }.thenBy { it.retrievedAt })
        best.copy(id = if (identitiesPerId.getValue(best.id) > 1) "S" + researchHash(key).take(16) else best.id, engines = versions.flatMap { it.engines }.distinct().take(20), providerReceipts = versions.flatMap { it.providerReceipts }.distinct())
    }
    fun questionIdentity(question: ResearchQuestion) = "Q" + researchHash(question.text.trim() + "\n" + question.evidenceNeeded.trim() + "\n" + question.json().toString().replace(question.id, "")).take(16)
    val questions = snapshots.flatMap { it.questionPlan }.map { it.copy(id = questionIdentity(it)) }.distinctBy { it.id }
    val claims = snapshots.flatMap { snapshot ->
        val questionIds = snapshot.questionPlan.associate { it.id to questionIdentity(it) }
        snapshot.claims.mapNotNull { claim ->
            val original = snapshot.sources.firstOrNull { researchClaimMatchesCapture(claim, it) } ?: return@mapNotNull null
            val retained = sourcesByIdentity[identity(original)] ?: return@mapNotNull null
            val remapped = claim.copy(sourceId = retained.id, questionId = questionIds[claim.questionId] ?: claim.questionId, facts = claim.facts.map { it.copy(sourceId = retained.id) })
            if (!researchClaimMatchesCapture(remapped, retained)) return@mapNotNull null
            remapped
        }
    }.distinctBy { listOf(it.sourceId, it.text, it.quote, it.questionId, it.reviewedSourceVersion, it.verdict) }
    return incoming.copy(
        sources = sourcesByIdentity.values.toList(),
        claims = claims,
        questionPlan = questions.ifEmpty { incoming.questionPlan },
        questions = questions.map { it.text }.ifEmpty { snapshots.flatMap { it.questions }.distinct() },
        queries = snapshots.flatMap { it.queries }.distinct(),
        toolEvents = snapshots.flatMap { it.toolEvents }.distinct().sortedBy { it.recordedAt }.takeLast(200)
    )
}
