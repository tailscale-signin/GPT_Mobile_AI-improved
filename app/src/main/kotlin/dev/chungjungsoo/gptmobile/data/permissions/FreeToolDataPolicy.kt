package dev.chungjungsoo.gptmobile.data.permissions

/** Only app-owned public tools are implicitly public. Unclassified plugins need permission. */
object FreeToolDataPolicy {
    private val publicTools = setOf(
        "web_search", "read_url", "current_date", "calculate_expression", "news", "crawler", "jina_crawler_fetch", "airbnb",
        "delegate_to_model", "read_recovery_context", "discover_tools", "github", "github_read", "github_search",
        "deep_research_search_papers", "deep_research_get_paper", "deep_research_read_paper", "deep_research_citations", "deep_research_verify_evidence", "deep_research_status"
    )
    fun requiresPersonalPermission(toolId: String): Boolean = toolId !in publicTools
}
