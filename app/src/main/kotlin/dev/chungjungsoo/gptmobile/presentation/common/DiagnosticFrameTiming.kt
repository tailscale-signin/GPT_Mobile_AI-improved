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
internal fun Modifier.diagnosticFrameTiming(screen: String): Modifier = diagnosticFrameTiming(CombinedOperationOwner(screen = screen))

/** Remember this modifier by its scalar owner fields so unrelated recomposition keeps its layout lambda. */
internal fun Modifier.diagnosticFrameTiming(owner: CombinedOperationOwner): Modifier {
    // Optional diagnostics must never make a screen unavailable. Do not sanitize
    // arbitrary labels into telemetry: they may contain rendered/private text.
    if (!owner.screen.matches(diagnosticScreenName) || owner.uiContext?.component?.matches(diagnosticScreenName) == false) {
        if (invalidTimingLabelReported.compareAndSet(false, true)) {
            AppLogRecorder.record("FrameTiming", "FRAME_TIMING_LABEL_REJECTED · measurementSkipped=true · scope=optional_instrumentation", "W")
        }
        return this
    }
    val prefix = "ui_${owner.screen}" + owner.uiContext?.let { "_${it.component}" }.orEmpty()
    val measureOperation = "${prefix}_measure"
    val layoutOperation = "${prefix}_layout"
    val drawOperation = "${prefix}_draw"
    return layout { measurable, constraints ->
        val measured = CombinedOperationTrace.measure(measureOperation, owner) { measurable.measure(constraints) }
        layout(measured.width, measured.height) {
            CombinedOperationTrace.measure(layoutOperation, owner) { measured.placeRelative(0, 0) }
        }
    }.drawWithContent {
        CombinedOperationTrace.measure(drawOperation, owner) { drawContent() }
    }
}
