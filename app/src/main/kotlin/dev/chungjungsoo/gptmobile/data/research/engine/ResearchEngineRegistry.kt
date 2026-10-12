package dev.chungjungsoo.gptmobile.data.research.engine

/** Eligibility is distinct from health. Pending contracts never silently activate. */
data class ResearchEngineManifest(
    val id: String,
    val name: String,
    val capability: String,
    val ready: Boolean = false,
    val limitation: String = "Operation, access and deployment terms validation pending"
)

object ResearchEngineRegistry {
    val entries = listOf(
        ResearchEngineManifest("crossref", "Crossref", "Metadata", true, "Anonymous quota; metadata is not full text"),
        ResearchEngineManifest("openalex", "OpenAlex", "Metadata", true, "Limited anonymous quota"),
        ResearchEngineManifest("datacite", "DataCite", "Dataset metadata", true, "Public records only"),
        ResearchEngineManifest("pubmed", "NCBI PubMed", "Metadata"),
        ResearchEngineManifest("europepmc", "Europe PMC", "Metadata / Full text", true, "Eligible open-access XML"),
        ResearchEngineManifest("pmc_oa", "PMC OA", "Full text", true, "Retrieval through Europe PMC; same corpus identity"),
        ResearchEngineManifest("arxiv", "arXiv", "Metadata / Full text", true, "Preprints; at least 3 seconds between requests"),
        ResearchEngineManifest("semantic_scholar", "Semantic Scholar", "Citation graph"),
        ResearchEngineManifest("opencitations", "OpenCitations", "Citation graph", true, "Identifier enrichment; not keyword search"),
        ResearchEngineManifest("openaire", "OpenAIRE Graph", "Metadata"),
        ResearchEngineManifest("doaj", "DOAJ", "Metadata feed"),
        ResearchEngineManifest("dblp", "dblp", "Metadata"),
        ResearchEngineManifest("hal", "HAL", "Metadata / OA leads", limitation = "Deployment and reuse terms review required"),
        ResearchEngineManifest("zenodo", "Zenodo", "Dataset"),
        ResearchEngineManifest("biorxiv", "bioRxiv", "Identifier enrichment"),
        ResearchEngineManifest("medrxiv", "medRxiv", "Identifier enrichment"),
        ResearchEngineManifest("osf", "OSF", "Public records"),
        ResearchEngineManifest("clinicaltrials", "ClinicalTrials.gov", "Trial report"),
        ResearchEngineManifest("nasa", "NASA NTRS / PubSpace", "Reports"),
        ResearchEngineManifest("openreview", "OpenReview", "Public reviews"),
        ResearchEngineManifest("acl", "ACL Anthology", "Local metadata index"),
        ResearchEngineManifest("eric", "ERIC", "Metadata"),
        ResearchEngineManifest("dataverse", "Dataverse", "Published datasets"),
        ResearchEngineManifest("figshare", "Figshare", "Public records"),
        ResearchEngineManifest("ebi_search", "EBI Search", "Specialist metadata"),
        ResearchEngineManifest("chebi", "ChEBI", "Chemical record"),
        ResearchEngineManifest("pdbe", "PDBe", "Structure record"),
        ResearchEngineManifest("dryad", "Dryad", "Dataset"),
        ResearchEngineManifest("orcid", "ORCID", "Identifier enrichment", limitation = "Anonymous capability and noncommercial terms review required"),
        ResearchEngineManifest("searxng", "SearXNG", "Web federation", limitation = "Configured instance must enable JSON and pass its access contract"),
        ResearchEngineManifest("arxiv_mcp", "arxiv-mcp-server", "Optional transport"),
        ResearchEngineManifest("paper_search_mcp", "paper-search-mcp", "Optional transport"),
        ResearchEngineManifest("pubmed_mcp", "PubMed MCP", "Optional transport"),
        ResearchEngineManifest("pubcrawl_mcp", "Pubcrawl MCP", "Optional transport"),
        ResearchEngineManifest("searxng_mcp", "SearXNG MCP", "Optional transport"),
        ResearchEngineManifest("huggingface_mcp", "Hugging Face MCP", "Public records"),
        ResearchEngineManifest("rival_search_mcp", "RivalSearchMCP", "Experimental transport", limitation = "Explicit opt-in, privacy and endpoint validation required")
    )
    val searchableIds = entries.filter { it.ready && it.capability.startsWith("Metadata") || it.id == "datacite" }.map { it.id }.toSet()
}
