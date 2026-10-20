package dev.chungjungsoo.gptmobile.data.research

internal fun identifierFromResearchUrl(url: String, namespace: String): String = runCatching {
    val uri = java.net.URI(url)
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path.orEmpty().trim('/')
    when (namespace) {
        "pmid" -> path.takeIf { host == "pubmed.ncbi.nlm.nih.gov" && it.matches(Regex("[0-9]{1,12}")) }.orEmpty()
        "pmcid" -> Regex("(?:^|/)articles/(PMC[0-9]{1,12})(?:/|$)", RegexOption.IGNORE_CASE).find(uri.path.orEmpty())?.groupValues?.get(1)?.uppercase()?.takeIf { host in setOf("pmc.ncbi.nlm.nih.gov", "www.ncbi.nlm.nih.gov") }.orEmpty()
        "doi" -> SourceIdentity.normalizeDoi(path).takeIf { host in setOf("doi.org", "dx.doi.org") }.orEmpty()
        else -> ""
    }
}.getOrDefault("")
