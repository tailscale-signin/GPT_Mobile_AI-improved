package com.example.github

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Public-client GitHub App authorization request using OAuth 2.0 PKCE (S256).
 * Callers must persist the pending verifier and state securely until callback,
 * validate state, then exchange the code using a trusted token exchange component.
 * Never place a GitHub App private key in an Android APK.
 */
internal object GitHubAppAuthorization {
    data class Pending(val authorizationUrl: String, val state: String, val verifier: String)

    fun start(clientId: String, callbackUrl: String): Pending {
        require(clientId.isNotBlank()) { "GitHub App client ID is required" }
        require(callbackUrl.startsWith("https://")) { "Use a verified HTTPS callback" }
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(32)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val url = Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", callbackUrl)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString()
        return Pending(url, state, verifier)
    }

    fun verifyCallback(callback: Uri, expectedState: String): String {
        require(callback.getQueryParameter("error") == null) { "GitHub authorization was declined" }
        val state = callback.getQueryParameter("state")
        require(!state.isNullOrBlank() && constantTimeEqual(state, expectedState)) {
            "GitHub authorization state mismatch"
        }
        return callback.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: error("GitHub authorization code missing")
    }

    private fun randomUrlSafe(bytes: Int): String {
        val value = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun constantTimeEqual(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
