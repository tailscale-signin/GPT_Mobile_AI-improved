package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkMetricFormatTest {
    @Test fun percentAndTextUnitsAreLiteralData() {
        assertEquals("85.71%", benchmarkMetricText(100.0 * 18 / 21, "%", locale = Locale.US))
        assertEquals("125.50 ms", benchmarkMetricText(125.5, "ms", locale = Locale.US))
        assertEquals("12.00 reference text units/s", benchmarkMetricText(12.0, "reference text units/s", locale = Locale.US))
        assertEquals("12.00 %s %% 100%", benchmarkMetricText(12.0, "%s %% 100%", locale = Locale.US))
        assertEquals("85,71%", benchmarkMetricText(100.0 * 18 / 21, "%", locale = Locale.GERMANY))
    }

    @Test fun absentAndInvalidMeasurementsRemainExplicit() {
        assertEquals("Not measured", benchmarkMetricText(null, "%"))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertEquals("Invalid measurement", benchmarkMetricText(it, "ms"))
        }
        assertEquals("0.00%", benchmarkMetricText(0.0, "%", locale = Locale.US))
    }
}
