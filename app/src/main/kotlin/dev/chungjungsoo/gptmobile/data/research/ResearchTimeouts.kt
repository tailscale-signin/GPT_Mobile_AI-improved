package dev.chungjungsoo.gptmobile.data.research

/** Physical HTTP deadlines are smaller than aggregate operations; queue time is not network time. */
internal object ResearchTimeouts {
    const val CONNECT_MS = 8_000L
    const val SOCKET_MS = 12_000L
    const val HTTP_ATTEMPT_MS = 20_000L
    const val HTTP_ATTEMPTS = 3
    const val MODEL_MS = 45_000L

    fun operationMillis(tool: String, pageMillis: Long): Long = when (tool) {
        "deep_research_search_papers", "deep_research_citations" -> maxOf(pageMillis, HTTP_ATTEMPT_MS * HTTP_ATTEMPTS * 3 + 10_000L)
        "deep_research_read_paper", "deep_research_get_paper" -> maxOf(pageMillis, HTTP_ATTEMPT_MS * HTTP_ATTEMPTS + 10_000L)
        else -> pageMillis.coerceAtLeast(1L)
    }

    /** A claim covers the academic read, one authorized page fallback and checkpoint time. */
    fun leaseMillis(tool: String, pageMillis: Long): Long {
        val operation = operationMillis(tool, pageMillis)
        val fallback = pageMillis.coerceAtLeast(1L)
        if (operation > Long.MAX_VALUE - fallback) return Long.MAX_VALUE
        val attempts = operation + fallback
        return if (attempts > Long.MAX_VALUE - 10_000L) Long.MAX_VALUE else attempts + 10_000L
    }

    fun isAcademicRead(tool: String): Boolean = tool in setOf(
        "deep_research_search_papers",
        "deep_research_get_paper",
        "deep_research_read_paper",
        "deep_research_citations",
        "deep_research_verify_evidence",
        "deep_research_status"
    )
}
