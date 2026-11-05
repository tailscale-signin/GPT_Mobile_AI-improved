package dev.chungjungsoo.gptmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFontFallbackPolicyTest {
    @Test fun standardSerifAndSansStylesKeepTheirFamilyAndRequestedEmphasis() {
        for (family in listOf("Times", "Helvetica")) {
            for ((suffix, style) in listOf("Bold" to "Bold", "Italic" to "Italic", "BoldItalic" to "BoldItalic")) {
                val name = if (family == "Helvetica") suffix.replace("Italic", "Oblique") else suffix
                val choices = PdfFontFallbackPolicy.candidates("$family-$name")
                assertTrue(choices.isNotEmpty())
                assertTrue(choices.all { it.endsWith("-$style.ttf") })
                if (family == "Times") assertTrue(choices.all { "Serif" in it })
            }
        }
    }

    @Test fun symbolsAndDingbatsRequireDifferentCoverage() {
        val symbol = PdfFontFallbackPolicy.candidates("Symbol")
        val dingbats = PdfFontFallbackPolicy.candidates("ZapfDingbats")
        assertFalse(symbol.isEmpty())
        assertFalse(dingbats.isEmpty())
        assertFalse(symbol.contains("NotoSansSymbols2-Regular.ttf"))
        assertTrue(dingbats.contains("NotoSansSymbols2-Regular.ttf"))
        assertEquals(symbol, PdfFontFallbackPolicy.candidates("ABCDEF+SymbolMT"))
        val dingbatGlyphs = PdfFontFallbackPolicy.requiredCodePoints("ZapfDingbats").toSet()
        assertTrue(PdfFontFallbackPolicy.hasRequiredCoverage("ZapfDingbats", dingbatGlyphs::contains))
        assertFalse(PdfFontFallbackPolicy.hasRequiredCoverage("Symbol", dingbatGlyphs::contains))
    }

    @Test fun greekAndMathematicalGlyphsMustBothBePresentBeforeReplacingSymbol() {
        val required = PdfFontFallbackPolicy.requiredCodePoints("Symbol").toSet()
        assertTrue(required.contains(0x3B1))
        assertTrue(required.contains(0x3A9))
        assertTrue(required.contains(0x2211))
        assertTrue(PdfFontFallbackPolicy.hasRequiredCoverage("Symbol", required::contains))
        assertFalse(PdfFontFallbackPolicy.hasRequiredCoverage("Symbol", (required - 0x3B1)::contains))
        assertFalse(PdfFontFallbackPolicy.hasRequiredCoverage("Symbol", (required - 0x2211)::contains))
        assertFalse(PdfFontFallbackPolicy.hasRequiredCoverage("Symbol") { false })
        assertFalse(PdfFontFallbackPolicy.hasRequiredCoverage("CustomBrandFont") { true })
    }

    @Test fun unknownFontsStayWithTheDefaultMapperAndSubsetsHaveStableMapping() {
        assertTrue(PdfFontFallbackPolicy.candidates("CustomBrandFont").isEmpty())
        assertEquals(PdfFontFallbackPolicy.candidates("Times-BoldItalic"), PdfFontFallbackPolicy.candidates("ABCDEF+TimesNewRomanPS-BoldItalicMT"))
    }
}
