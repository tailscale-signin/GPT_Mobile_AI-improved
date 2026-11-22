package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.MaskedProviderCall
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request

/** One request's owner, retained across engine queues and native callback threads. */
internal class CapturedRequestPrivacy private constructor(
    val conversation: ConversationPrivacyContext?,
    val maskedProvider: Boolean,
    private val context: CoroutineContext
) {
    fun <T> withPolicy(block: () -> T): T = ConversationPrivacyContext.withCaptured(conversation, block)

    /** Permissions and masking settings may change while OkHttp is waiting for a connection. */
    fun recheckAuthorization() {
        try {
            context.ensureActive()
            context[RequestAuthorizationGuard]?.let { guard ->
                val policy = conversation ?: ConversationPrivacyContext(null, false)
                runBlocking(context.minusKey(Job).minusKey(ContinuationInterceptor) + policy) { guard.validate() }
            }
            context.ensureActive()
        } catch (failure: Exception) {
            // OkHttp's asynchronous boundary reports IOExceptions to its caller.
            throw IOException("DISPATCH_POLICY_CHANGED: The request's originating conversation or permissions changed.", failure)
        }
    }

    companion object {
        fun capture(context: CoroutineContext): CapturedRequestPrivacy = CapturedRequestPrivacy(
            context[ConversationPrivacyContext] ?: ConversationPrivacyContext.capture(),
            context[MaskedProviderCall.Key] != null,
            context
        )
    }
}

/**
 * Ktor's OkHttp engine does not copy request attributes into native tags. A one-use
 * ticket carries the trusted attribute across that boundary and is stripped before
 * transport. Neither a user header nor the network thread's current chat is authority.
 */
internal object ConversationPrivacyHttp {
    private val attribute = AttributeKey<CapturedRequestPrivacy>("conversationPrivacy")
    internal const val HEADER = "X-GPTMobile-Internal-Conversation"
    private val pending = ConcurrentHashMap<String, CapturedRequestPrivacy>()

    suspend fun capture(request: HttpRequestBuilder): CapturedRequestPrivacy = request.attributes.getOrNull(attribute)
        ?: CapturedRequestPrivacy.capture(currentCoroutineContext()).also { request.attributes.put(attribute, it) }

    internal fun ticket(captured: CapturedRequestPrivacy, owner: Job?): String {
        val ticket = UUID.randomUUID().toString()
        pending[ticket] = captured
        owner?.invokeOnCompletion { pending.remove(ticket) }
        return ticket
    }

    internal fun bind(request: Request): Request {
        val ticket = request.header(HEADER)
        val captured = if (ticket == null) request.tag(CapturedRequestPrivacy::class.java) else pending.remove(ticket)
        if (captured == null) throw IOException("PRIVACY_REQUEST_OWNER_MISSING: The request has no captured conversation policy.")
        return request.newBuilder().removeHeader(HEADER).tag(CapturedRequestPrivacy::class.java, captured).build()
    }

    val bridge = Interceptor { chain -> chain.proceed(bind(chain.request())) }

    fun policy(request: Request): CapturedRequestPrivacy = request.tag(CapturedRequestPrivacy::class.java)
        ?: throw IOException("PRIVACY_REQUEST_OWNER_MISSING: The request has no captured conversation policy.")
}

internal val OkHttpConversationPrivacy = createClientPlugin("OkHttpConversationPrivacy") {
    onRequest { request, _ -> ConversationPrivacyHttp.capture(request) }
    on(SendingRequest) { request, _ ->
        val captured = ConversationPrivacyHttp.capture(request)
        request.headers.remove(ConversationPrivacyHttp.HEADER)
        request.headers.append(ConversationPrivacyHttp.HEADER, ConversationPrivacyHttp.ticket(captured, currentCoroutineContext()[Job]))
    }
}
