package dev.chungjungsoo.gptmobile.data.network.gateway

/** Public metadata contains fixed repair categories, never an upstream body or exception message. */
internal object GatewayReadinessFailure {
    private val reasonCodes = setOf(
        "BACKEND_CONNECTION_REFUSED", "BACKEND_CONNECT_TIMEOUT", "BACKEND_READ_TIMEOUT", "BACKEND_DNS_FAILED",
        "BACKEND_TLS_FAILED", "BACKEND_CONNECTION_CLOSED", "BACKEND_CONNECTION_FAILED", "BACKEND_HTTP_ERROR",
        "BACKEND_METADATA_DENIED", "BACKEND_ROUTE_UNSUPPORTED", "BACKEND_INVALID_RESPONSE", "BACKEND_RESPONSE_TOO_LARGE",
        "BACKEND_METADATA_DEADLINE", "BACKEND_CONFIGURATION_INVALID", "SELECTED_MODEL_NOT_AVAILABLE", "SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING",
        "SELECTED_MODEL_UNVERIFIED", "BACKEND_GENERATION_UNAVAILABLE", "BACKEND_GENERATION_UNVERIFIED",
        "GATEWAY_DRAINING", "DURABLE_STORAGE_UNAVAILABLE", "DURABLE_STORAGE_WRITE_FAILED"
    )
    private val failureCodes = setOf(
        "EXECUTION_METADATA_DEADLINE", "EXECUTION_METADATA_DENIED", "EXECUTION_METADATA_OVERSIZE",
        "EXECUTION_CAPABILITY_UNSUPPORTED", "EXECUTION_CAPABILITY_INVALID", "EXECUTION_BACKEND_UNAVAILABLE",
        "EXECUTION_MODEL_IDENTITY_INVALID", "EXECUTION_MODEL_UNAVAILABLE", "EXECUTION_MODEL_NOT_LOADED", "EXECUTION_MODEL_LOADING",
        "EXECUTION_MODEL_MISMATCH", "EXECUTION_CONTEXT_UNAVAILABLE"
    )
    private val dependencies = setOf("model_backend", "backend_health", "backend_models", "backend_properties", "backend_template", "selected_model", "durable_storage", "process")
    private val categories = setOf(
        "connection_refused", "connect_timeout", "read_timeout", "dns", "tls", "connection_closed", "connection", "http",
        "denied", "unsupported", "invalid_response", "oversize", "deadline", "configuration", "model_unavailable",
        "model_not_loaded", "model_loading", "generation_unavailable", "unverified", "storage", "draining"
    )
    private val upstreamCodes = setOf("model_not_loaded", "model_loading", "model_not_found", "invalid_model", "invalid_request", "invalid_request_error", "not_supported", "permission_denied", "unauthorized", "internal_error", "server_error")
    private val upstreamTypes = setOf("invalid_request_error", "authentication_error", "permission_error", "not_found_error", "server_error", "unavailable_error", "not_supported_error")
    private val upstreamMessages = setOf("MODEL_NOT_LOADED", "MODEL_LOADING", "MODEL_NOT_FOUND", "MODEL_LOAD_FAILED", "INVALID_API_KEY", "ENDPOINT_DISABLED")

    fun reasonCode(value: String?): String? = value?.takeIf { it in reasonCodes }
    fun failureCode(value: String?): String? = value?.takeIf { it in failureCodes }
    fun dependency(value: String?): String? = value?.takeIf { it in dependencies }
    fun category(value: String?): String? = value?.takeIf { it in categories }
    fun modelState(value: String?): String? = value?.takeIf { it in setOf("UNKNOWN", "UNAVAILABLE", "UNLOADED", "LOADING", "LOADED", "SLEEPING") }
    fun upstreamCode(value: String?): String? = value?.takeIf { it in upstreamCodes || it.length == 3 && it.toIntOrNull() in 100..599 }
    fun upstreamType(value: String?): String? = value?.takeIf { it in upstreamTypes }
    fun upstreamMessage(value: String?): String? = value?.takeIf { it in upstreamMessages }
    fun upstreamBodyState(value: String?): String? = value?.takeIf { it in setOf("PARSED", "INVALID", "TOO_LARGE", "UNAVAILABLE", "DEADLINE") }
}
