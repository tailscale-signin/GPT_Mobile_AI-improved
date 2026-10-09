package dev.chungjungsoo.gptmobile.data.agent.tool

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class ValidatedSearchUrl(val key: String, val navigationUrl: String, val policyVersion: Int = 2)

/** Comparison keys never replace navigation links. Unknown query/fragment semantics remain intact. */
internal object SearchUrlIdentity {
    fun parse(value: String): ValidatedSearchUrl? = runCatching {
        val original = value.trim()
        val uri = URI(original)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http", "https") && !uri.isOpaque && uri.rawUserInfo == null)
        val authority = requireNotNull(uri.rawAuthority)
        require('@' !in authority && '\\' !in original)
        val parsedPort = if (uri.host == null && ':' in authority) requireNotNull(authority.substringAfterLast(':').toIntOrNull()) else uri.port
        val rawHost = uri.host ?: authority.substringBeforeLast(':', authority)
        val host = if (rawHost.startsWith('[')) rawHost.lowercase(Locale.ROOT) else IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(host.isNotBlank() && parsedPort in -1..65535 && parsedPort != 0)
        val port = parsedPort.takeUnless { it == -1 || (it == 80 && scheme == "http") || (it == 443 && scheme == "https") }
        val query = uri.rawQuery?.split('&')?.filterNot { part ->
            val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name()).lowercase(Locale.ROOT)
            name.startsWith("utm_") || name in setOf("fbclid", "gclid", "msclkid")
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        val key = "$scheme://$host${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().ifEmpty { "/" }}" +
            query?.let { "?$it" }.orEmpty() + uri.rawFragment?.let { "#$it" }.orEmpty()
        ValidatedSearchUrl(key, original)
    }.getOrNull()
}

/** Compatibility facade for readers and evidence collectors; invalid URLs never become clickable sources. */
internal fun canonicalSearchUrl(url: String): String = SearchUrlIdentity.parse(url)?.key ?: url
