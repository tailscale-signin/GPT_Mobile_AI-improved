package dev.chungjungsoo.gptmobile.data.research.academic

import java.net.URI

/** Same-publisher HTML alternatives; callers still enforce domain, provider, consent and budget policy. */
internal fun academicHtmlAlternative(raw: String): String {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
    if (uri.scheme != "https" || uri.userInfo != null) return raw
    val path = uri.path.orEmpty()
    val html = when (uri.host?.lowercase()) {
        "www.nature.com", "nature.com" -> if (path.startsWith("/articles/") && path.endsWith(".pdf")) path.removeSuffix(".pdf").removeSuffix("_reference") else path
        "www.mdpi.com", "mdpi.com" -> path.removeSuffix("/pdf")
        else -> path
    }
    return if (html == path) raw else URI(uri.scheme, null, uri.host, uri.port, html, null, null).toString()
}
