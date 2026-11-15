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
    val capturedTag = capturedAndroidPrefix.find(diagnosticRecorderPayload(message))?.groupValues?.get(1)?.trim()?.lowercase()
    val gatewayAdmission = classifyGatewayAdmission(tag, text)
    val transcriptionCode = if (tag == "Android" && capturedTag == "moonshinejni") {
        moonshineTranscriptionCode.find(text)?.groupValues?.get(1)?.toIntOrNull()
    } else {
        null
    }
    return when {
        // An attributed fatal event must never be downgraded by a vendor setting,
        // optional model feature, or benign platform phrase elsewhere in its stack.
        tag == "AppCrash" || "fatal exception" in text || level == "F" -> DiagnosticClassification("fatal", "critical", true)
        tag == "ProcessExit" && Regex("(?:^| · )reasoncode=(?:4|5)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("historical_process_crash", "error", true, "inspect_same_process_exit_and_crash_receipt")
        tag == "ProcessExit" && Regex("(?:^| · )reasoncode=6(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("historical_anr", "warning", true, "inspect_same_process_anr_receipt")
        tag == "ProcessExit" -> DiagnosticClassification("historical_process_exit", "info", false, "use_os_exit_reason_without_attributing_nearby_exceptions")
        tag == "VoiceEngine" &&
            text.startsWith("moonshine_failure") &&
            "cancelled=false" in text &&
            Regex("(?:^| · )failurecode=(?!none(?: · |$))[a-z0-9_]+").containsMatchIn(text) ->
            DiagnosticClassification("voice_capture_or_recognition_failure", "warning", true, "inspect_native_operation_and_capture_state")
        tag == "VoiceEngine" &&
            text.startsWith("moonshine_") &&
            (text.startsWith("moonshine_released") || text.startsWith("moonshine_decoder_close") || "cancelled=true" in text) ->
            DiagnosticClassification("native_voice_cleanup", "info", false)
        transcriptionCode == 0 -> DiagnosticClassification("native_transcription_success", "info", false)
        transcriptionCode != null -> DiagnosticClassification("native_operation_failure", "error", true, "inspect_operation_receipt")
        tag == "Android" && capturedTag == "sherpa-onnx" && isSherpaConfigurationRecord(text) ->
            DiagnosticClassification("native_voice_configuration", "info", false)
        tag == "VoiceTiming" && text.startsWith("kokoro_terminal") && Regex("(?:^| · )reason=(?:timeout|engine_failed|synthesis_failed|audio_failed|text_queue_full|memory_pressure|pronunciation_unavailable|lexicon_unavailable)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("voice_playback_failure", "warning", true, "review_playback_timing_and_selected_voice")
        tag == "Android" && capturedTag == "androidruntime" && text.startsWith("process:") -> DiagnosticClassification("crash_context", "info", false)
        tag == "VoiceRecognition" && Regex("(?:^| · )errorcode=speech_service_(?:start_failed|start_timeout|unavailable)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("speech_service_start", "warning", true, "check_service_availability_and_respect_binding_cooldown")
        tag == "VoiceRecognition" && Regex("^recognition_error=10(?: · |$)").containsMatchIn(text) && "failedstage=recognition" !in text ->
            DiagnosticClassification("speech_service_or_rate_limit", "warning", true, "inspect_recognition_phase_before_attributing_error_ten")
        tag == "VoiceRecognition" && Regex("^recognition_error=10(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("speech_rate_limit", "warning", true, "respect_shared_recognition_cooldown")
        tag == "VoiceRecognition" && Regex("^recognition_error=9(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("speech_permission", "warning", true, "restore_microphone_permission")
        tag == "Android" && Regex("(?:stop|getvoices|shutdown) failed: not bound to tts engine").containsMatchIn(text) ->
            DiagnosticClassification("speech_engine_lifecycle", "warning", true, "retire_unbound_engine_before_retry")
        tag == "Android" && "didn't find workspec for id" in text ->
            DiagnosticClassification("work_scheduler_reconciliation", "warning", true, "reconcile_work_id_and_generation_with_durable_task")
        tag == "Android" && "callback not found for callback_available message" in text ->
            DiagnosticClassification("network_callback_lifecycle", "warning", true, "inspect_callback_registration_and_cleanup")
        "oom_recovery" in text || "out_of_memory_recovered" in text -> DiagnosticClassification("memory_recovery", "warning", true, "retained_state_after_oom")
        gatewayAdmission != null -> gatewayAdmission
        text.startsWith("run_terminal") && Regex("(?:^| · )status=(?:partial|blocked|evidence_missing)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("task_incomplete", "warning", true, "resume_pending_work_or_resolve_blocker")
        "outofmemoryerror" in text || "failed to allocate" in text && "bytes" in text -> DiagnosticClassification("fatal", "critical", true, "reduce_peak_memory")
        text.startsWith("synthesis_quality") && "completed_with_issues" in text -> DiagnosticClassification("combined_contract", "warning", true, "retain_answer_and_review_quality")
        "required_work_unfunded" in text || "optional combined work allowance" in text -> DiagnosticClassification("admission", "warning", true, "retain_pending_work")
        "outcome=invalid_response" in text || "status=invalid_response" in text || "schema_validation_failed" in text -> DiagnosticClassification("research_schema", "error", true, "repair_structured_output")
        ("delivery" in text && "accepted=false" in text) || "correction_required" in text -> DiagnosticClassification("delivery_contract", "warning", true, "retain_evidence_and_repair_report")
        tag == "MCP" && (text.startsWith("connect start") || text.startsWith("connected")) -> DiagnosticClassification("connectivity_progress", "info", false)
        ("strictmode" in text || capturedTag == "strictmode") && "networkviolation" in text -> DiagnosticClassification("main_thread_network_io", "warning", true, "move_network_and_socket_cleanup_to_io")
        "strictmode" in text && ("diskreadviolation" in text || "diskwriteviolation" in text) -> DiagnosticClassification("main_thread_disk_io", "warning", true, "move_work_to_io")
        "method exceeds compiler instruction limit" in text || "method exceeds compiler" in text -> DiagnosticClassification("compiler_method_size", "warning", true, "split_coroutine_stages")
        text.startsWith("synthesis_quality") && ("missing_contribution_link" in text || "word_target_undershot" in text || "word_limit_exceeded" in text || "exact_word_count_unmet" in text) -> DiagnosticClassification("combined_contract", "warning", true, "retain_answer_and_review_quality")
        text.startsWith("discovery_negotiation") && ("outcome=legacy_fallback" in text || "outcome=legacy_initialization_required" in text) -> DiagnosticClassification("compatibility_probe", "info", false)
        text.startsWith("gguf_template_metadata") || text.startsWith("gguf_template_compatible") -> DiagnosticClassification("model_compatibility", "info", false)
        text.startsWith("gguf_template_rejected") -> DiagnosticClassification("model_compatibility", "error", true, "choose_compatible_gguf_template")
        tag == "MCP" && text.startsWith("connect failed") -> DiagnosticClassification("mcp_connectivity", "error", true, "check_server_address_and_reachability")
        tag == "MCP" && text.startsWith("health") && ("state=unreachable" in text || "state=degraded" in text) -> DiagnosticClassification("mcp_connectivity", "warning", true, "check_server_address_and_reachability")
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
        text.startsWith("android_memory_trim") &&
            level !in setOf("E", "W") &&
            Regex("(?:^| · )level=(?:20|40)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("memory_trim_lifecycle", "info", false, "background_cleanup_observed_low_memory_not_inferred")
        text.startsWith("android_memory_trim") || text.startsWith("android_low_memory") -> DiagnosticClassification("memory_pressure", if (level == "E") "error" else "warning", true)
        text.startsWith("catalog_canceled") -> DiagnosticClassification("cancellation", "info", false)
        tag == "Android" &&
            level == "W" &&
            capturedTag == "jobinfo" &&
            "requested important-while-foreground flag" in text &&
            "is ignored and takes no effect" in text ->
            DiagnosticClassification("platform_job_hint", "info", false)
        tag == "Android" && capturedTag == "libc" && text == "access denied finding property \"ro.hardware.chipname\"" ->
            DiagnosticClassification("platform_optional_property", "info", false)
        tag == "Android" && capturedTag == "system" && text.startsWith("uncaught exception thrown by finalizer") ->
            DiagnosticClassification("platform_finalizer_exception", "warning", true, "capture_complete_stack_and_os_exit_before_attributing_process_failure")
        "asus_leak_detect_proj" in text -> DiagnosticClassification(
            "protected_settings_access",
            if (level == "E") "error" else "warning",
            true,
            "capture_complete_stack_and_android_build_before_attributing_origin"
        )
        tag == "Android" && capturedTag == "imebackdispatcher" && text.startsWith("ime callback not found. ignoring unregisterreceivedcallback.") -> DiagnosticClassification("platform_input_cleanup", "info", false)
        tag == "Android" && capturedTag == "interactionjankmonitor" && text.startsWith("initializing without read_device_config permission. enabled=false") -> DiagnosticClassification("platform_optional_monitor", "info", false)
        tag == "Android" &&
            level == "W" &&
            capturedTag == "remoteinputconnectionimpl" &&
            Regex("^(?:gettextbeforecursor|gettextaftercursor|getselectedtext|requestcursorupdates) on inactive inputconnection$").matches(text) ->
            DiagnosticClassification("input_lifecycle", "info", false, "inspect_focus_lifecycle_if_input_loss_is_observed")
        "inactive inputconnection" in text -> DiagnosticClassification("input_lifecycle", "warning", true, "release_composer_focus_before_screen_or_editor_detaches")
        tag == "Android" && "systemjobservice" in text && "onnetworkchanged" in text && "not implemented" in text ->
            DiagnosticClassification("work_scheduler_compatibility", "warning", true, "verify_workmanager_and_android_build_with_background_recovery")
        tag == "Android" && "reducing the number of considered missed gc histogram windows" in text ->
            DiagnosticClassification("gc_sampling_window", "info", false, "profile_allocations_before_attributing_memory_leak")
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
        text.startsWith("enrichment_waiting_") ||
            text.startsWith("enrichment_deferred") ||
            text.startsWith("enrichment_local_fallback") ||
            text.startsWith("enrichment_lifecycle") &&
            "deferred=true" in text -> DiagnosticClassification("deferred_work", "info", false, "waiting")
        text.startsWith("connectivity_recovered") -> DiagnosticClassification("recovery", "info", false, "response_observed_on_current_network")
        tag == "Gateway" && text.startsWith("metadata_read_outcome") && "outcome=completed" in text ->
            DiagnosticClassification("recovery", "info", false, "metadata_read_completed_model_readiness_not_inferred")
        tag == "Gateway" && text.startsWith("client_exchange") && Regex("(?:^| · )transport=(?:timeout|transport_failure|connect_timeout|read_timeout|stream_interrupted|stream_idle_timeout|connection_aborted|dns_failed|dns_failure|route_unavailable|connection_refused|connection_reset)(?: · |$)").containsMatchIn(text) ->
            DiagnosticClassification("transport_availability", "warning", true, "correlate_metadata_read_attempts_and_final_outcome")
        text.startsWith("search_index_ready") ||
            text.startsWith("refresh_ok") ||
            text.startsWith("enrichment_success") ||
            text.startsWith("connected") ->
            DiagnosticClassification("recovery", "info", false, "reported_success")
        Regex("unexpected end of stream|broken pipe|unknownhostexception|unable to resolve host|connectexception|failed to connect|connect timeout|socketexception.*(?:abort|closed|reset)|socket abort|connection abort|connection reset", RegexOption.IGNORE_CASE).containsMatchIn(text) -> DiagnosticClassification("transport_availability", "warning", true, "bounded_route_recovery")
        level !in setOf("E", "W") && tag in setOf("Frame", "Rendering") && text.startsWith("frame_timing") ->
            if (significantFrameWindow(diagnosticEventMessage(message))) DiagnosticClassification("performance", "warning", true, "profile_main_thread_and_rendering") else DiagnosticClassification("performance_sample", "info", false)
        level == "E" -> DiagnosticClassification("error", "error", true)
        level == "W" -> DiagnosticClassification("warning", "warning", true)
        Regex("(?:^| · )(?:error=true|status=FAILED|outcome=FAILED)(?: · |$)", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
            DiagnosticClassification("reported_failure", "error", true)
        tag in setOf("Frame", "Rendering") && Regex("(?:slowFrames|jankyFrames|jankFrames)=[1-9]").containsMatchIn(diagnosticEventMessage(message)) ->
            DiagnosticClassification("performance", "warning", true)
        (capturedTag == "choreographer" || "choreographer" in text) && Regex("skipped [1-9][0-9]* frames").containsMatchIn(text) ->
            DiagnosticClassification("performance", "warning", true)
        else -> DiagnosticClassification("informational", "info", false)
    }
}

/** Only structured gateway/header evidence selects admission guidance; a status never identifies a credential fault. */
private fun classifyGatewayAdmission(tag: String?, text: String): DiagnosticClassification? {
    if (tag !in setOf("Gateway", "Network")) return null
    val headerCode = Regex("(?:^|\\s)x-gateway-admission-error\\s*:\\s*([a-z_]+)(?=$|\\s|[·,])").find(text)?.groupValues?.get(1)
    val structured = text.startsWith("admission_read") || text.startsWith("client_exchange")
    val code = headerCode ?: if (structured) Regex("(?:^| · )(?:admissioncode|code)=([a-z_]+)(?: · |$)").find(text)?.groupValues?.get(1) else null
    if (text.startsWith("admission_read") &&
        code == "command_receipt_unavailable" &&
        " · codesource=gateway · " in text &&
        Regex("(?:^| · )surface=command(?: · |$)").containsMatchIn(text) &&
        Regex("(?:^| · )httpstatus=404(?: · |$)").containsMatchIn(text)
    ) {
        return DiagnosticClassification("gateway_command_reconciliation", "info", false, "retain_command_and_reconcile_original_gateway")
    }
    val recovery = when (code) {
        "remote_surface_not_admitted" -> "verify_gateway_source_and_saved_work_contract"
        "scope_not_granted", "engineering_scope_required", "tool_scope_required_for_runtime", "local_admin_required" -> "check_original_gateway_scope"
        "transport_identity_required" -> "check_configured_gateway_transport_identity"
        "continuity_identity_mismatch", "continuity_identity_invalid" -> "restore_original_gateway_identity"
        "invalid_host", "invalid_origin", "duplicate_admission_header", "browser_csrf_required" -> "check_gateway_destination_and_access_mode"
        "conversation_storage_disabled", "conversation_storage_unavailable" -> "restore_gateway_durable_storage"
        else -> null
    }
    if (recovery != null) return DiagnosticClassification("gateway_admission", "warning", true, recovery)
    if (structured && Regex("(?:^| · )surface=identity(?: · |$)").containsMatchIn(text) && Regex("(?:^| · )http(?:status)?=403(?: · |$)").containsMatchIn(text)) {
        return DiagnosticClassification("gateway_admission", "warning", true, "inspect_gateway_admission_code_and_source_contract")
    }
    return null
}

/** Keep isolated missed deadlines visible without reporting every high-refresh sample as a fault. */
private fun significantFrameWindow(message: String): Boolean {
    fun value(name: String): Double? = Regex("(?:^| · )$name=([0-9]+(?:\\.[0-9]+)?)").find(message)?.groupValues?.get(1)?.toDoubleOrNull()
    if ((value("stallsOver100Ms") ?: 0.0) > 0 || (value("maxMs") ?: 0.0) >= 100 || (value("droppedSamples") ?: 0.0) > 0) return true
    val missed = value("jankFrames") ?: value("jankyFrames") ?: value("slowFrames") ?: return false
    val frames = value("frames") ?: return false
    return FrameJankPolicy.sustained(frames.toLong(), missed.toLong())
}

/** Strip recorder metadata without swallowing the event label. */
private fun diagnosticRecorderPayload(message: String): String = message.replace(Regex("^(?:(?:eventId=[A-Za-z0-9_.:-]+|monoNs=[0-9]+|bootMs=(?:[0-9]+|unknown)|processId=-?[0-9]+|processName=[A-Za-z0-9_.:-]+|processSessionId=[A-Za-z0-9_.:-]+)(?: · | +))+"), "")

private val capturedAndroidPrefix = Regex("^[VDIWEF]/([^(:]+)(?:\\(\\s*([0-9]+)\\))?:\\s*")
private val moonshineTranscriptionCode = Regex("\\btranscription error:\\s*(-?[0-9]+)\\s*$")
private val sherpaConfigurationRoot = Regex("^(?:config:\\s*)?(?:offlinettsconfig|offlinettsmodelconfig|offlinettskokoromodelconfig)\\(")

/** Sherpa's JNI writes config headers/dumps at ERROR level; mentions in actual failures are not dumps. */
private fun isSherpaConfigurationRecord(text: String): Boolean {
    val value = text.trim()
    // The upstream newFromFile diagnostic uses "config:\n%s" before Validate().
    // Its following "Errors found in config!" remains an actionable error.
    if (value == "config:") return true
    val root = sherpaConfigurationRoot.find(value) ?: return false
    var depth = 1
    var quoted = false
    var escaped = false
    for (index in root.range.last + 1 until value.length) {
        val char = value[index]
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted && char == '(' -> depth++
            !quoted && char == ')' -> {
                depth--
                if (depth == 0) return value.substring(index + 1).isBlank()
            }
        }
    }
    // Unrecognized or truncated fragments retain their recorded severity.
    return false
}

internal fun diagnosticStructuredEvent(message: String): StructuredDiagnosticEvent? =
    parseStructuredDiagnosticEvent(diagnosticRecorderPayload(message).replace(capturedAndroidPrefix, ""))

internal fun diagnosticEventMessage(message: String): String {
    val payload = diagnosticRecorderPayload(message).replace(capturedAndroidPrefix, "")
    return parseStructuredDiagnosticEvent(payload)?.summary() ?: payload
}

/** Stable source identity includes the original Android tag and PID, never recorder severity. */
internal fun diagnosticAndroidSource(message: String): String? = capturedAndroidPrefix.find(diagnosticRecorderPayload(message))?.let { match ->
    "Android:${match.groupValues[1].trim()}:${match.groupValues[2].ifBlank { "unscoped" }}"
}
