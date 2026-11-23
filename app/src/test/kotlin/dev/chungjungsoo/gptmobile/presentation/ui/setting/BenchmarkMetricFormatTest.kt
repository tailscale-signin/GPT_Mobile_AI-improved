package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkMetricFormatTest {
    @Test fun fractionalMicroCurrencyCostDoesNotRoundToFree() {
        assertEquals("< 0.000001 USD", benchmarkApiCostText(.1, "USD", Locale.US))
        assertEquals("< 0,000001 EUR", benchmarkApiCostText(.1, "EUR", Locale.GERMANY))
        assertEquals("0.000001 USD", benchmarkApiCostText(1.0, "USD", Locale.US))
        assertEquals("0.000000 USD", benchmarkApiCostText(0.0, "USD", Locale.US))
    }

    @Test fun unknownCurrencyAndUsageAreNotFilledIn() {
        assertEquals("No API charge", benchmarkApiCostText(0.0, null, Locale.US))
        assertEquals("Currency not recorded", benchmarkApiCostText(1.0, null, Locale.US))
        assertEquals("Not measured", benchmarkApiCostText(null, "USD", Locale.US))
        assertEquals("Invalid measurement", benchmarkApiCostText(-1.0, "USD", Locale.US))
    }

    @Test fun configuredTokenCostRetainsMicroCurrencyPrecision() {
        assertEquals("0.000050 USD", benchmarkMetricText(50.0 / 1_000_000, "USD", decimals = 6, locale = Locale.US))
        assertEquals("0.000000 USD", benchmarkMetricText(0.0, "USD", decimals = 6, locale = Locale.US))
        assertEquals("0,000050 EUR", benchmarkMetricText(50.0 / 1_000_000, "EUR", decimals = 6, locale = Locale.GERMANY))
        assertEquals("1.23 USD", benchmarkMetricText(1.23, "USD", decimals = Int.MAX_VALUE, locale = Locale.US))
    }

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
