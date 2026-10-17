package dev.chungjungsoo.gptmobile.data.diagnostics

/** Classification is deliberately separate from duplicate detection and never removes evidence. */
internal data class DiagnosticClassification(
    val category: String,
    val severity: String,
    val actionable: Boolean,
    val recovery: String = "not_reported"
)

internal fun classifyDiagnostic(level: String?, tag: String?, message: String): DiagnosticClassification {
    val text = message.lowercase()
    return when {
        text.startsWith("discovery_negotiation") && "outcome=legacy_fallback" in text -> DiagnosticClassification("compatibility_probe", "info", false)
        text.startsWith("catalog_canceled") -> DiagnosticClassification("cancellation", "info", false)
        "asus_leak_detect_proj" in text -> DiagnosticClassification("platform", "warning", false)
        "inactive inputconnection" in text -> DiagnosticClassification("input_lifecycle", "warning", true)
        "getenv: not attached" in text -> DiagnosticClassification("native_thread_attachment", "info", false)
        "feedback manager requires a model with a single signature" in text -> DiagnosticClassification("optional_model_feature", "info", false)
        tag == "Android" &&
            Regex("(?:audio|video)capabilities.*unsupported (?:mediatype|mime)").containsMatchIn(text) ->
            DiagnosticClassification("platform_codec_capability", "info", false)
        tag == "Android" &&
            "adrenovk" in text &&
            ("shader compilation failed" in text || "pipeline create failed" in text) ->
            DiagnosticClassification("graphics_driver_failure", "warning", true)
        text.startsWith("enrichment_waiting_") -> DiagnosticClassification("deferred_work", "info", false, "waiting")
        text.startsWith("search_index_ready") ||
            text.startsWith("refresh_ok") ||
            text.startsWith("enrichment_success") ||
            text.startsWith("connected") ->
            DiagnosticClassification("recovery", "info", false, "reported_success")
        tag == "AppCrash" || "fatal exception" in text || level == "F" -> DiagnosticClassification("fatal", "critical", true)
        level == "E" -> DiagnosticClassification("error", "error", true)
        level == "W" -> DiagnosticClassification("warning", "warning", true)
        Regex("(?:^| · )(?:error=true|status=FAILED|outcome=FAILED)(?: · |$)", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
            DiagnosticClassification("reported_failure", "error", true)
        tag in setOf("Frame", "Rendering") && Regex("(?:slowFrames|jankyFrames|jankFrames)=[1-9]").containsMatchIn(message) ->
            DiagnosticClassification("performance", "warning", true)
        "choreographer" in text && Regex("skipped [1-9][0-9]* frames").containsMatchIn(text) ->
            DiagnosticClassification("performance", "warning", true)
        else -> DiagnosticClassification("informational", "info", false)
    }
}
