package dev.chungjungsoo.gptmobile.util

import android.content.Context
import com.tom_roush.fontbox.FontBoxFont
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.font.CIDFontMapping
import com.tom_roush.pdfbox.pdmodel.font.FontMapper
import com.tom_roush.pdfbox.pdmodel.font.FontMappers
import com.tom_roush.pdfbox.pdmodel.font.FontMapping
import com.tom_roush.pdfbox.pdmodel.font.PDCIDSystemInfo
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import java.io.File
import java.lang.ref.SoftReference

/** Extends PDFBox's default mapper without replacing embedded fonts or downloading font assets. */
internal object AndroidPdfFonts {
    @Synchronized fun initialize(context: Context) {
        PDFBoxResourceLoader.init(context)
        val mapper = FontMappers.instance()
        if (mapper !is AndroidFontMapper) FontMappers.set(AndroidFontMapper(mapper))
    }

    internal class AndroidFontMapper(
        private val delegate: FontMapper,
        private val loadFont: (String) -> TrueTypeFont? = { name ->
            val file = File("/system/fonts", name)
            // Parsing a stream leaves no open system-font file descriptor in the cache.
            if (file.isFile) runCatching { file.inputStream().use { TTFParser().parse(it) } }.getOrNull() else null
        }
    ) : FontMapper {
        private val fonts = mutableMapOf<String, SoftReference<TrueTypeFont>>()

        @Synchronized private fun resolve(baseFont: String?): TrueTypeFont? {
            for (name in PdfFontFallbackPolicy.candidates(baseFont)) {
                val font = fonts[name]?.get() ?: loadFont(name)?.also { fonts[name] = SoftReference(it) } ?: continue
                val compatible = runCatching {
                    val cmap = font.unicodeCmapLookup
                    PdfFontFallbackPolicy.hasRequiredCoverage(baseFont) { cmap.getGlyphId(it) != 0 }
                }.getOrDefault(false)
                if (compatible) return font
            }
            return null
        }

        override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<TrueTypeFont> =
            resolve(baseFont)?.let { FontMapping(it, true) } ?: delegate.getTrueTypeFont(baseFont, fontDescriptor)

        override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<FontBoxFont> =
            resolve(baseFont)?.let { FontMapping<FontBoxFont>(it, true) } ?: delegate.getFontBoxFont(baseFont, fontDescriptor)

        override fun getCIDFont(baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?): CIDFontMapping =
            delegate.getCIDFont(baseFont, fontDescriptor, cidSystemInfo)
    }
}
