package dev.chungjungsoo.gptmobile.data.research

/** Callers admit one authority and turn root. Contributor-local task labels are not task identity. */
internal fun mergeResearchSnapshots(previous: List<ResearchSnapshot>, incoming: ResearchSnapshot): ResearchSnapshot {
    val snapshots = if (isResearchConversationOnly(incoming.task)) listOf(incoming) else previous + incoming
    fun canonical(source: ResearchSource) = source.equivalenceGroup.ifBlank { source.canonicalUrl.ifBlank { source.url } } to source.versionId
    val aliases = mutableMapOf<String, String>()
    val sources = snapshots.flatMap { it.sources }.groupBy(::canonical).values.map { versions ->
        val best = versions.maxWith(compareBy<ResearchSource> { it.readable }.thenBy { it.status == "Read" }.thenBy { it.passage.length }.thenBy { it.retrievedAt })
        versions.forEach { aliases[it.id] = best.id }
        best.copy(engines = versions.flatMap { it.engines }.distinct().take(20))
    }
    fun questionIdentity(question: ResearchQuestion) = "Q" + researchHash(question.text.trim() + "\n" + question.evidenceNeeded.trim()).take(16)
    val questions = snapshots.flatMap { it.questionPlan }.map { it.copy(id = questionIdentity(it)) }.distinctBy { it.id }
    val claims = snapshots.flatMap { snapshot ->
        val questionIds = snapshot.questionPlan.associate { it.id to questionIdentity(it) }
        snapshot.claims.map { claim -> claim.copy(sourceId = aliases[claim.sourceId] ?: claim.sourceId, questionId = questionIds[claim.questionId] ?: claim.questionId) }
    }.filter { claim -> sources.any { it.id == claim.sourceId && it.readable && claim.quote in it.passage } }
        .associateBy { listOf(it.sourceId, it.text, it.quote, it.questionId) }.values.toList()
    return incoming.copy(
        sources = sources,
        claims = claims,
        questionPlan = questions.ifEmpty { incoming.questionPlan },
        questions = questions.map { it.text }.ifEmpty { incoming.questions },
        queries = snapshots.flatMap { it.queries }.distinct(),
        toolEvents = snapshots.flatMap { it.toolEvents }.distinct().sortedBy { it.recordedAt }.takeLast(200)
    )
}
