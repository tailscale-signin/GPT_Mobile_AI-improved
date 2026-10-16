package dev.chungjungsoo.gptmobile.data.nvidia

import dev.chungjungsoo.gptmobile.data.ModelConstants
import java.net.URI

/** Canonical chat/model API base, not a URL that includes an individual operation path. */
object NvidiaEndpoints {
    fun normalize(raw: String): String {
        val supplied = raw.trim().ifBlank { ModelConstants.NVIDIA_API_URL }.trimEnd('/')
        val uri = URI(supplied)
        val scheme = uri.scheme?.lowercase()
        require(scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
            "NVIDIA API endpoint must be a valid HTTP(S) URL."
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Put NVIDIA credentials in the key field, not in the URL."
        }
        val host = uri.host
        if (host.equals("integrate.api.nvidia.com", ignoreCase = true)) {
            require(scheme == "https" && uri.port in setOf(-1, 443)) {
                "The hosted NVIDIA API requires HTTPS."
            }
        }
        val path = uri.path.trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/models")
            .removeSuffix("/responses")
        val normalizedPath = if (host.equals("integrate.api.nvidia.com", ignoreCase = true) && path.isBlank()) {
            "/v1"
        } else {
            path
        }
        return URI(scheme, null, host, uri.port, normalizedPath, null, null).toASCIIString().trimEnd('/')
    }
}
