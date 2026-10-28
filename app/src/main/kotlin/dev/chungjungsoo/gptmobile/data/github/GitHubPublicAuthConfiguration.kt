package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.BuildConfig
import java.net.URI

internal data class GitHubPublicAuthConfiguration(val clientId: String, val brokerOrigin: String, val callbackUri: String, val appSlug: String = "") {
    val configured: Boolean get() = clientId.isNotBlank() && brokerOrigin.isNotBlank()

    fun installationManagementUrl(connectionClientId: String?): String =
        if (clientId.isNotBlank() && connectionClientId == clientId && appSlug.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?"))) {
            "https://github.com/apps/$appSlug"
        } else {
            "https://github.com/settings/installations"
        }

    fun acceptsCallback(value: String): Boolean {
        if (!configured || value.length > 8192) return false
        val actual = runCatching { URI(value) }.getOrNull() ?: return false
        val expected = runCatching { URI(callbackUri) }.getOrNull() ?: return false
        return actual.scheme == "https" && actual.rawAuthority == expected.rawAuthority && actual.rawPath == expected.rawPath && actual.userInfo == null && actual.fragment == null
    }

    fun acceptsAuthorizationLaunch(value: String): Boolean = runCatching {
        if (!configured || value.length > 8192) return false
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host != "github.com" || uri.path != "/login/oauth/authorize" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return false
        val pairs = uri.rawQuery.orEmpty().split('&').map { pair ->
            java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return false
        val params = pairs.toMap()
        params["client_id"] == clientId && params["redirect_uri"] == callbackUri && params["code_challenge_method"] == "S256"
    }.getOrDefault(false)

    companion object {
        fun project() = GitHubPublicAuthConfiguration(BuildConfig.GITHUB_OAUTH_CLIENT_ID, BuildConfig.GITHUB_AUTH_BROKER_ORIGIN, BuildConfig.GITHUB_AUTH_CALLBACK_URI, BuildConfig.GITHUB_APP_SLUG)
    }
}
