package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import java.util.concurrent.atomic.AtomicBoolean

private val diagnosticScreenName = Regex("[A-Za-z0-9_]{1,40}")
private val invalidTimingLabelReported = AtomicBoolean(false)

/** Opt-in phase timings use structural screen labels, never the rendered text or model output. */
internal fun Modifier.diagnosticFrameTiming(screen: String): Modifier {
    // Optional diagnostics must never make a screen unavailable. Do not sanitize
    // arbitrary labels into telemetry: they may contain rendered/private text.
    if (!screen.matches(diagnosticScreenName)) {
        if (invalidTimingLabelReported.compareAndSet(false, true)) {
            AppLogRecorder.record("FrameTiming", "FRAME_TIMING_LABEL_REJECTED · measurementSkipped=true · scope=optional_instrumentation", "W")
        }
        return this
    }
    val owner = CombinedOperationOwner(screen = screen)
    return layout { measurable, constraints ->
        val measured = CombinedOperationTrace.measure("ui_${screen}_measure", owner) { measurable.measure(constraints) }
        layout(measured.width, measured.height) {
            CombinedOperationTrace.measure("ui_${screen}_layout", owner) { measured.placeRelative(0, 0) }
        }
    }.drawWithContent {
        CombinedOperationTrace.measure("ui_${screen}_draw", owner) { drawContent() }
    }
}
