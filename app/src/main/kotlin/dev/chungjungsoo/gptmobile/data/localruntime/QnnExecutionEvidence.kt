package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Last confirmed boundary, never a fabricated success for an internal native stage. */
internal object QnnExecutionEvidence {
    @Volatile var executionQualified: Boolean = false
        private set

    @Volatile var lastStatus: String = "NOT_QUALIFIED"
        private set

    fun invalidate() {
        executionQualified = false
    }

    fun record(requestId: String, event: String, stage: QnnRuntimeStage, detail: String) {
        if (event == "QNN_INIT_BEGIN" || "status=FAILED" in detail || "status=NOT_QUALIFIED" in detail || "status=CANCELLED" in detail) executionQualified = false
        if ("status=QUALIFIED" in detail) executionQualified = true
        lastStatus = "$event · requestId=$requestId · stage=$stage · ${safeQnnDetail(detail)}"
        AppLogRecorder.checkpoint("QNN", "$lastStatus · appCommit=${BuildConfig.APP_COMMIT} · litert=${BuildConfig.LITERT_LM_VERSION} · qairt=${BuildConfig.QAIRT_VERSION} · vendor_log_available=false")
    }
}
