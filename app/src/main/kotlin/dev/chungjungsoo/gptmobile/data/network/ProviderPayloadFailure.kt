package dev.chungjungsoo.gptmobile.data.network

/** An error inside HTTP 200 reached the provider; it is not a transport or DNS failure. */
internal fun providerPayloadFailure(code: String?, message: String, toolChoice: String?, toolsAdvertised: Boolean): FreeFailureCategory {
    val detail = "${code.orEmpty()} $message".lowercase()
    return when {
        ("tool_use_failed" in detail || "tool_choice" in detail) && (toolChoice == "none" || !toolsAdvertised) -> FreeFailureCategory.TOOL_CHOICE_MISMATCH
        "invalid_api_key" in detail || "authentication" in detail -> FreeFailureCategory.AUTHENTICATION_REJECTED
        "rate_limit" in detail || code == "429" -> FreeFailureCategory.PROVIDER_RATE_LIMITED
        else -> FreeFailureCategory.PROVIDER_PAYLOAD_ERROR
    }
}
