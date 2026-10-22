package dev.chungjungsoo.gptmobile.data.network

import java.net.URI

object LocalServerUrls {
    fun root(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Enter an HTTP(S) server URL without credentials or a query." }
        return uri.toString().removeSuffix("/api/tags").removeSuffix("/api").removeSuffix("/v1")
    }
    fun openAiBase(value: String): String = "${root(value)}/v1"
    fun ollamaTags(value: String): String = "${root(value)}/api/tags"
}
