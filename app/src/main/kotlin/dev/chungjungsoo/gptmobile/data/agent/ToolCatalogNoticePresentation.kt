package dev.chungjungsoo.gptmobile.data.agent

/** Presentation only: authorization and per-tool diagnostics remain with the catalog resolver. */
internal object ToolCatalogNoticePresentation {
    private val privacyDenial = Regex("^([^:\\r\\n]+): (PERSONAL_DATA_LOCAL_ONLY|PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED): .+$")

    fun notices(unavailable: List<String>): List<String> {
        val denials = unavailable.map { privacyDenial.matchEntire(it) }
        val counts = denials.filterNotNull().groupBy { it.groupValues[2] }.mapValues { (_, matches) -> matches.map { it.groupValues[1] }.distinct().size }
        val emitted = mutableSetOf<String>()
        return unavailable.mapIndexedNotNull { index, notice ->
            val code = denials[index]?.groupValues?.get(2)
            val count = counts[code] ?: 0
            when {
                code == null || count < 2 -> notice
                !emitted.add(code) -> null
                code == "PERSONAL_DATA_LOCAL_ONLY" -> "$count external tool entries are unavailable for this request because its context is local-only (PERSONAL_DATA_LOCAL_ONLY). Other authorized repository tools may remain available. Use a request without that local-only context to access these tools."
                else -> "$count external tool entries are unavailable because sharing permission is missing for their services (PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED). Use a new conversation with a nonpersonal query, or keep the work with the approved model."
            }
        }
    }
}
