package dev.chungjungsoo.gptmobile.data.health

/** Never returns provider exception text, record values, tokens or package-specific diagnostics. */
internal data class HealthReadFailure(val code: String, val retryable: Boolean, val action: String) {
    companion object {
        fun classify(failure: Throwable): HealthReadFailure {
            val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
            return when {
                causes.any { it is SecurityException } -> HealthReadFailure("permission_required", false, "Grant the required Health Connect read permission, keep the app open and unlock the phone.")
                causes.any { it is IllegalArgumentException } -> HealthReadFailure("invalid_health_request", false, "Check the requested dates, record type and provider compatibility. Use a recent time range.")
                causes.any { it.javaClass.simpleName in setOf("TimeoutCancellationException", "SocketTimeoutException", "TimeoutException") } -> HealthReadFailure("health_connect_timeout", true, "Health Connect did not respond in time. Keep the app open and retry.")
                causes.any { it.javaClass.simpleName in setOf("DeadObjectException", "RemoteException") } -> HealthReadFailure("health_connect_ipc_failure", true, "The Health Connect service disconnected. Keep the app open and retry.")
                causes.any { it is java.io.IOException } -> HealthReadFailure("health_connect_io_failure", true, "Health Connect could not complete the read. Retry after the service recovers.")
                else -> HealthReadFailure("health_connect_unavailable", false, "Check Health Connect availability and permissions in plugin settings.")
            }
        }

        fun safeCode(code: String?): String = code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "health_connect_unavailable"
    }
}
