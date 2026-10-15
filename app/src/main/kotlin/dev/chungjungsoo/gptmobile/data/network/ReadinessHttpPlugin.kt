package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.currentCoroutineContext

/** Install only with the matching OkHttp stripping interceptor and event listener. */
internal val ReadinessHttpPlugin = createClientPlugin("ReadinessHttpTrace") {
    onRequest { request, _ ->
        currentCoroutineContext()[RequestDispatchContext]?.takeHttpTrace()?.let { trace ->
            request.headers.remove(ReadinessHttpTrace.HEADER)
            request.headers.append(ReadinessHttpTrace.HEADER, ReadinessHttpTrace.register(trace))
        }
    }
}
