package dev.melo.gptmobile.improved.presentation.ui.localmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.melo.gptmobile.improved.BuildConfig
import dev.melo.gptmobile.improved.data.huggingface.HuggingFaceUrls
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface HuggingFaceSignInResult {
    data class Success(val accessToken: String) : HuggingFaceSignInResult
    data object Cancelled : HuggingFaceSignInResult
    data object Failed : HuggingFaceSignInResult
}

class HuggingFaceAuthClient(
    private val context: Context,
    private val authService: AuthorizationService = AuthorizationService(context)
) {
    fun authorizationIntent(): Intent? {
        val clientId = BuildConfig.HUGGING_FACE_CLIENT_ID
        if (clientId.isBlank()) return null
        val serviceConfig = AuthorizationServiceConfiguration(
            HuggingFaceUrls.AUTH_ENDPOINT.toUri(),
            HuggingFaceUrls.TOKEN_ENDPOINT.toUri()
        )
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(HuggingFaceUrls.REDIRECT_URI)
        ).setScopes(HuggingFaceUrls.SCOPES)
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    suspend fun completeSignIn(data: Intent?): HuggingFaceSignInResult {
        if (data == null) return HuggingFaceSignInResult.Cancelled
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        if (exception != null && response == null) {
            return if (exception.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                exception.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code
            ) {
                HuggingFaceSignInResult.Cancelled
            } else {
                HuggingFaceSignInResult.Failed
            }
        }
        if (response == null) return HuggingFaceSignInResult.Cancelled
        return suspendCancellableCoroutine { continuation ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
                val accessToken = tokenResponse?.accessToken
                when {
                    accessToken != null -> continuation.resume(HuggingFaceSignInResult.Success(accessToken))
                    tokenException != null -> continuation.resume(HuggingFaceSignInResult.Failed)
                    else -> continuation.resume(HuggingFaceSignInResult.Cancelled)
                }
            }
        }
    }

    fun dispose() {
        authService.dispose()
    }
}
