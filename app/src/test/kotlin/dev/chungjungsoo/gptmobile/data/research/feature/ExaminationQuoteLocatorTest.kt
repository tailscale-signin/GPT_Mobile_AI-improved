package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationQuoteLocatorTest {
    @Test fun typographyWhitespaceAndUnicodeCompositionResolveToActualSourceBytes() {
        val source = "🧪 Results: cafe\u0301\u00a0 patients’ outcomes\twere similar."
        val found = ExaminationQuoteLocator(source).locate("café patients' outcomes were similar.")
        assertEquals(source.substring(found.start, found.end), found.quote)
        assertEquals(source.indexOf("cafe"), found.start)
        assertTrue(found.quote.contains('\u00a0'))
        assertTrue(found.quote.contains('’'))
    }

    @Test fun ambiguousNormalizedMatchesNeedAnExactOffsetAndParaphrasesAreNeverAccepted() {
        val source = "patients’ outcomes; patients' outcomes"
        val locator = ExaminationQuoteLocator(source)
        assertTrue(runCatching { locator.locate("patientsʼ outcomes") }.isFailure)
        assertEquals(source.lastIndexOf("patients"), locator.locate("patientsʼ outcomes", source.lastIndexOf("patients")).start)
        assertTrue(runCatching { locator.locate("patients improved") }.isFailure)
        assertTrue(runCatching { ExaminationQuoteLocator("10 mM").locate("10 mm") }.isFailure)
        assertTrue(runCatching { ExaminationQuoteLocator("🧪").locate("\uDDEA", 1) }.isFailure)
    }
}
