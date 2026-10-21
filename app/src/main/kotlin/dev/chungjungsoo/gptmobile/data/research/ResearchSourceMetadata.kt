package dev.chungjungsoo.gptmobile.data.research

/** Enrich only bytes already captured under the accepted scope; no provider is enabled here. */
fun enrichResearchSourceMetadata(source: ResearchSource, captured: String): ResearchSource {
    val reference = Regex("(?im)^\\s*(?:references|bibliography)\\s*[:\\n]|\\bReferences\\s*\\n", RegexOption.IGNORE_CASE).find(captured)?.range?.first
    val front = captured.take(reference ?: captured.length).take(16000)
    val document = if (front.contains("<meta", true)) org.jsoup.Jsoup.parse(front) else null
    fun meta(name: String) = document?.selectFirst("meta[name=$name]")?.attr("content").orEmpty()
    val doi = SourceIdentity.normalizeDoi(source.doi.ifBlank { meta("citation_doi") }.ifBlank { Regex("(?i)\\bdoi\\s*:?\\s*(?:https?://(?:dx\\.)?doi\\.org/)?(10\\.[0-9]{4,9}/[^\\s<>]+)").find(front)?.groupValues?.get(1).orEmpty() })
    val pmcid = source.pmcid.ifBlank { identifierFromResearchUrl(source.url, "pmcid") }.ifBlank { Regex("(?i)\\b(PMC[0-9]{1,12})\\b").find(front)?.value?.uppercase().orEmpty() }
    val pmid = source.pmid.ifBlank { identifierFromResearchUrl(source.url, "pmid") }.ifBlank { meta("citation_pmid") }.ifBlank { Regex("(?i)\\bPMID\\s*:?\\s*([0-9]{1,12})\\b").find(front)?.groupValues?.get(1).orEmpty() }
    val labeled = meta("citation_article_type").ifBlank { Regex("(?i)(?:publication type|article type|study design)\\s*:\\s*([^\\n.]{1,100})").find(front)?.groupValues?.get(1).orEmpty() }
    val designText = (source.title + " " + labeled).lowercase().replace('-', ' ')
    val designs = buildSet {
        if (Regex("randomi[sz]ed").containsMatchIn(designText)) add("RANDOMIZED_TRIAL")
        if ("controlled" in designText) add("CONTROLLED_TRIAL")
        if ("observational" in designText || "cohort" in designText || "case control" in designText) add("OBSERVATIONAL")
        if ("systematic review" in designText) add("SYSTEMATIC_REVIEW")
        if ("meta analys" in designText) add("META_ANALYSIS")
    }
    val type = source.publicationType.takeUnless { it.isBlank() || it == "unknown" } ?: labeled.ifBlank { designs.firstOrNull()?.lowercase() ?: "unknown" }
    return source.copy(
        doi = doi,
        pmid = pmid,
        pmcid = pmcid,
        publicationType = type,
        studyDesigns = source.studyDesigns + designs,
        equivalenceGroup = SourceIdentity.create(source.url, source.title, source.passage, doi, pmid, pmcid, source.publicationYear).equivalenceKey,
        metadataProvenance = (source.metadataProvenance + "authorized_capture:${researchHash(captured)}").distinct()
    )
}
