package com.example.gptmobileai.debug

object AetherionMaxConfig {
    // Sampling rates
    const val METRICS_SAMPLING_MS = 100L
    const val HARDWARE_SAMPLING_MS = 5000L
    const val NETWORK_CHECK_INTERVAL_MS = 30000L

    // Storage
    const val MAX_TELEMETRY_EVENTS = 10_000
    const val MAX_SESSION_METRICS = 1000

    // UI
    const val DEBUG_MODE_UI_ENABLED = true
    const val SHOW_TOOL_METRICS_IN_CHAT = true
    const val SHOW_TOKEN_TIMELINE = true

    // Performance
    const val BACKPRESSURE_THRESHOLD_MS = 50L
    const val MIN_THROUGHPUT_TPS = 1.0
}
