package dev.chungjungsoo.gptmobile.util

import com.tom_roush.fontbox.FontBoxFont
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.CIDFontMapping
import com.tom_roush.pdfbox.pdmodel.font.FontMapper
import com.tom_roush.pdfbox.pdmodel.font.FontMapping
import com.tom_roush.pdfbox.pdmodel.font.PDCIDSystemInfo
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AndroidPdfFontsTest {
    private class Delegate(private val fallback: TrueTypeFont) : FontMapper {
        var calls = 0
        override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<TrueTypeFont> {
            calls++
            return FontMapping(fallback, true)
        }
        override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<FontBoxFont> {
            calls++
            return FontMapping(fallback, true)
        }
        override fun getCIDFont(baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?): CIDFontMapping =
            CIDFontMapping(null, fallback, true)
    }

    private fun font(glyphs: Set<Int>): TrueTypeFont {
        val font = mock(TrueTypeFont::class.java)
        `when`(font.unicodeCmapLookup).thenReturn(object : CmapLookup {
            override fun getGlyphId(codePoint: Int): Int = if (codePoint in glyphs) codePoint else 0
            override fun getCharCodes(glyphId: Int): MutableList<Int> = if (glyphId in glyphs) mutableListOf(glyphId) else mutableListOf()
        })
        return font
    }

    @Test fun aCachedDingbatFontCannotReplaceSymbolWhenGreekLettersAreMissing() {
        val dingbats = font(PdfFontFallbackPolicy.requiredCodePoints("ZapfDingbats").toSet())
        val fallback = font(emptySet())
        val delegate = Delegate(fallback)
        var loads = 0
        val mapper = AndroidPdfFonts.AndroidFontMapper(delegate) { name ->
            if (name == "NotoSansSymbols-Regular.ttf") {
                loads++
                dingbats
            } else {
                null
            }
        }
        assertSame(dingbats, mapper.getTrueTypeFont("ZapfDingbats", null).font)
        assertSame(fallback, mapper.getTrueTypeFont("Symbol", null).font)
        assertSame(fallback, mapper.getFontBoxFont("Symbol", null).font)
        assertEquals(1, loads)
        assertEquals(2, delegate.calls)
    }

    @Test fun symbolSubstitutionRequiresGreekAndMathematicalCoverage() {
        val supported = font(PdfFontFallbackPolicy.requiredCodePoints("Symbol").toSet())
        val fallback = font(emptySet())
        val delegate = Delegate(fallback)
        val mapper = AndroidPdfFonts.AndroidFontMapper(delegate) { name -> supported.takeIf { name == "NotoSansMath-Regular.ttf" } }
        assertSame(supported, mapper.getFontBoxFont("ABCDEF+SymbolMT", null).font)
        assertEquals(0, delegate.calls)
        assertSame(fallback, mapper.getTrueTypeFont("UnrecognizedFont", null).font)
        assertEquals(1, delegate.calls)
    }
}
