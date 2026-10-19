package dev.chungjungsoo.gptmobile.data.research

/** Merge only inside a previously admitted authority scope. Readable evidence wins over a late failed reader. */
internal fun mergeResearchSnapshots(previous: List<ResearchSnapshot>, incoming: ResearchSnapshot): ResearchSnapshot {
    fun canonical(source: ResearchSource) = source.equivalenceGroup.ifBlank { source.canonicalUrl.ifBlank { source.url } } to source.versionId
    val sources = (previous.flatMap { it.sources } + incoming.sources).groupBy(::canonical).values.map { versions ->
        val best = versions.maxWith(compareBy<ResearchSource> { it.readable }.thenBy { it.retrievedAt })
        best.copy(engines = versions.flatMap { it.engines }.distinct().take(20))
    }
    val ids = sources.map { it.id }.toSet()
    return incoming.copy(sources = sources, claims = (previous.flatMap { it.claims } + incoming.claims).filter { it.sourceId in ids }.associateBy { listOf(it.sourceId, it.text, it.quote) }.values.toList(), queries = (previous.flatMap { it.queries } + incoming.queries).distinct(), toolEvents = (previous.flatMap { it.toolEvents } + incoming.toolEvents).distinct().sortedBy { it.recordedAt }.takeLast(200))
}
