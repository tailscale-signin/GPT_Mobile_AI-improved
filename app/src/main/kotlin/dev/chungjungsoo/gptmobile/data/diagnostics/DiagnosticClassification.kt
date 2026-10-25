package dev.chungjungsoo.gptmobile.data.diagnostics

/** Classification is deliberately separate from duplicate detection and never removes evidence. */
internal data class DiagnosticClassification(
    val category: String,
    val severity: String,
    val actionable: Boolean,
    val recovery: String = "not_reported"
)

internal fun classifyDiagnostic(level: String?, tag: String?, message: String): DiagnosticClassification {
    val text = diagnosticEventMessage(message).lowercase()
    val capturedTag = capturedAndroidPrefix.find(diagnosticRecorderPayload(message))?.groupValues?.get(1)?.lowercase()
    return when {
        "oom_recovery" in text || "out_of_memory_recovered" in text -> DiagnosticClassification("memory_recovery", "warning", true, "retained_state_after_oom")
        "outofmemoryerror" in text || "failed to allocate" in text && "bytes" in text -> DiagnosticClassification("fatal", "critical", true, "reduce_peak_memory")
        text.startsWith("synthesis_quality") && "completed_with_issues" in text -> DiagnosticClassification("combined_contract", "warning", true, "retain_answer_and_review_quality")
        "required_work_unfunded" in text || "optional combined work allowance" in text -> DiagnosticClassification("admission", "warning", true, "retain_pending_work")
        "outcome=invalid_response" in text || "status=invalid_response" in text || "schema_validation_failed" in text -> DiagnosticClassification("research_schema", "error", true, "repair_structured_output")
        ("delivery" in text && "accepted=false" in text) || "correction_required" in text -> DiagnosticClassification("delivery_contract", "warning", true, "retain_evidence_and_repair_report")
        tag == "MCP" && (text.startsWith("connect start") || text.startsWith("connected")) -> DiagnosticClassification("connectivity_progress", "info", false)
        "strictmode" in text && ("diskreadviolation" in text || "diskwriteviolation" in text) -> DiagnosticClassification("main_thread_disk_io", "warning", true, "move_work_to_io")
        "method exceeds compiler instruction limit" in text || "method exceeds compiler" in text -> DiagnosticClassification("compiler_method_size", "warning", true, "split_coroutine_stages")
        text.startsWith("synthesis_quality") && ("missing_contribution_link" in text || "word_target_undershot" in text || "word_limit_exceeded" in text || "exact_word_count_unmet" in text) -> DiagnosticClassification("combined_contract", "warning", true, "retain_answer_and_review_quality")
        text.startsWith("discovery_negotiation") && "outcome=legacy_fallback" in text -> DiagnosticClassification("compatibility_probe", "info", false)
        text.startsWith("gguf_template_metadata") || text.startsWith("gguf_template_compatible") -> DiagnosticClassification("model_compatibility", "info", false)
        text.startsWith("gguf_template_rejected") -> DiagnosticClassification("model_compatibility", "error", true, "choose_compatible_gguf_template")
        tag == "MCP" && text.startsWith("connect failed") -> DiagnosticClassification("mcp_connectivity", "error", true, "check_server_address_and_reachability")
        tag == "MCP" && ("timed out" in text || "connection refused" in text || "connect_timeout" in text || "outcome=timeout" in text) -> DiagnosticClassification("mcp_connectivity", "warning", true, "check_server_address_and_reachability")
        text.startsWith("worker_termination_intent") -> DiagnosticClassification("termination_intent", "info", false, "exit_confirmation_pending")
        text.startsWith("load_failed") ||
            text.startsWith("create_failed") ||
            text.startsWith("send_failed") ||
            text.startsWith("native_operation_failed") ||
            text.startsWith("last_native_error") ->
            DiagnosticClassification("native_operation_failure", "error", true, "inspect_operation_receipt")
        text.startsWith("native_warning") -> DiagnosticClassification("native_warning", "warning", true, "inspect_operation_receipt")
        text.startsWith("worker_connection_lost") -> DiagnosticClassification("worker_connection_loss", "warning", true, "exit_confirmation_pending")
        text.startsWith("native_cleanup_receipt") && ("safeforsuccessor=false" in text || "quarantined=true" in text) ->
            DiagnosticClassification("native_cleanup_failure", "error", true, "wait_for_cleanup_confirmation")
        text.startsWith("worker_cleanup_failed") || text.startsWith("cleanup_failed") ->
            DiagnosticClassification("native_cleanup_failure", "error", true, "wait_for_cleanup_confirmation")
        text.startsWith("metadata_inspection_wait") || text.startsWith("metadata_request_coalesced") ->
            DiagnosticClassification("metadata_inspection_wait", "info", false, "await_existing_inspection")
        text.startsWith("native_operation_interrupted") -> DiagnosticClassification("native_exit_evidence", if ("exit=low_memory" in text) "warning" else "error", true)
        text.startsWith("android_memory_trim") || text.startsWith("android_low_memory") -> DiagnosticClassification("memory_pressure", if (level == "E") "error" else "warning", true)
        text.startsWith("catalog_canceled") -> DiagnosticClassification("cancellation", "info", false)
        tag == "Android" &&
            level == "W" &&
            capturedTag == "jobinfo" &&
            "requested important-while-foreground flag" in text &&
            "is ignored and takes no effect" in text ->
            DiagnosticClassification("platform_job_hint", "info", false)
        "asus_leak_detect_proj" in text -> DiagnosticClassification("platform", "warning", false)
        "inactive inputconnection" in text -> DiagnosticClassification("input_lifecycle", "warning", true)
        "getenv: not attached" in text -> DiagnosticClassification("native_thread_attachment", "info", false)
        "feedback manager requires a model with a single signature" in text -> DiagnosticClassification("optional_model_feature", "info", false)
        tag == "Android" &&
            capturedTag in setOf("audiocapabilities", "videocapabilities") &&
            Regex("unsupported (?:mediatype|mime)").containsMatchIn(text) ->
            DiagnosticClassification("platform_codec_capability", "info", false)
        tag == "Android" &&
            capturedTag?.startsWith("adrenovk") == true &&
            ("shader compilation failed" in text || "pipeline create failed" in text) ->
            DiagnosticClassification("graphics_driver_failure", "warning", true)
        text.startsWith("enrichment_waiting_") -> DiagnosticClassification("deferred_work", "info", false, "waiting")
        text.startsWith("connectivity_recovered") -> DiagnosticClassification("recovery", "info", false, "response_observed_on_current_network")
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
        (capturedTag == "choreographer" || "choreographer" in text) && Regex("skipped [1-9][0-9]* frames").containsMatchIn(text) ->
            DiagnosticClassification("performance", "warning", true)
        else -> DiagnosticClassification("informational", "info", false)
    }
}

/** Strip recorder metadata without swallowing the event label. */
private fun diagnosticRecorderPayload(message: String): String = message.replace(Regex("^(?:(?:eventId=[A-Za-z0-9_.:-]+|monoNs=[0-9]+|bootMs=(?:[0-9]+|unknown)|processId=-?[0-9]+|processName=[A-Za-z0-9_.:-]+)(?: · | +))+"), "")

private val capturedAndroidPrefix = Regex("^[VDIWEF]/([^(:]+)(?:\\(\\s*[0-9]+\\))?:\\s*")

internal fun diagnosticEventMessage(message: String): String = diagnosticRecorderPayload(message).replace(capturedAndroidPrefix, "")
