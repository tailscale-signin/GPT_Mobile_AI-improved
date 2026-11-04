package dev.chungjungsoo.gptmobile.data.network

/** A gateway can finish a mutation before its first stream event; missing output is not proof of non-execution. */
internal class ProviderDispatchRetryGuard(config: ProviderRequestConfig) {
    private val serverToolsAllowed = config.extraHeaders.any { (key, value) ->
        key.equals("X-Gateway-Allow-Local-Tools", ignoreCase = true) && !value.equals("false", ignoreCase = true)
    }
    private var dispatched = false

    fun dispatched() {
        dispatched = true
    }

    val remoteOutcomeUnknown: Boolean get() = serverToolsAllowed && dispatched

    fun rejectedBeforeExecution() {
        dispatched = false
    }

    fun mayRetry(hasPayload: Boolean): Boolean = !hasPayload && !remoteOutcomeUnknown
}
