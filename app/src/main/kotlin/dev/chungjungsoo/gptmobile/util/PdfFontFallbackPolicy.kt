package dev.chungjungsoo.gptmobile.util

/** Preserve requested styles and reject substitutions with insufficient Unicode coverage. */
internal object PdfFontFallbackPolicy {
    private val subsetPrefix = Regex("^[A-Z]{6}\\+")
    private val nameSeparators = Regex("[-_, ]")

    private fun canonical(baseFont: String?): String = baseFont.orEmpty().replace(subsetPrefix, "").lowercase()
        .replace(nameSeparators, "").replace("timesnewroman", "times").replace("timesps", "times").removeSuffix("mt")

    private fun family(name: String): String? = when (name) {
        "times", "timesroman", "timesbold", "timesitalic", "timesbolditalic" -> "serif"
        "helvetica", "helveticabold", "helveticaoblique", "helveticaboldoblique", "helveticaitalic", "helveticabolditalic", "arial", "arialbold", "arialitalic", "arialbolditalic" -> "sans"
        "symbol" -> "symbol"
        "zapfdingbats", "zapfdingbatsitc", "zapfdingbatsitcbybtregular", "dingbats" -> "dingbats"
        else -> null
    }

    fun candidates(baseFont: String?): List<String> {
        val name = canonical(baseFont)
        val family = family(name) ?: return emptyList()
        // Symbols2 covers dingbats, but not the Greek alphabet used by Symbol.
        // File names alone are insufficient: the mapper also verifies the cmap.
        if (family == "symbol") return listOf("NotoSansMath-Regular.ttf", "NotoSansSymbols-Regular.ttf", "NotoSans-Regular.ttf", "Roboto-Regular.ttf")
        if (family == "dingbats") return listOf("NotoSansSymbols2-Regular.ttf", "NotoSansSymbols-Regular-Subsetted2.ttf", "NotoSansSymbols-Regular-Subsetted.ttf", "NotoSansSymbols-Regular.ttf")
        val bold = "bold" in name
        val italic = "italic" in name || "oblique" in name
        val style = when {
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        }
        return if (family == "serif") {
            listOf("NotoSerif-$style.ttf", "DroidSerif-$style.ttf", "LiberationSerif-$style.ttf")
        } else {
            listOf("Roboto-$style.ttf", "RobotoStatic-$style.ttf", "NotoSans-$style.ttf", "LiberationSans-$style.ttf")
        }
    }

    /** Representative Latin, Greek/mathematical, and dingbat glyphs must map to nonzero IDs. */
    fun requiredCodePoints(baseFont: String?): List<Int> = when (family(canonical(baseFont))) {
        "serif", "sans" -> listOf(0x20, 0x41, 0x61, 0x30, 0xE9)
        "symbol" -> listOf(0x391, 0x3A9, 0x3B1, 0x3C9, 0x2126, 0x2202, 0x2206, 0x2211, 0x221A, 0x221E, 0x222B, 0x2264, 0x2265)
        "dingbats" -> listOf(0x2701, 0x2702, 0x2713, 0x2717, 0x2726, 0x2764, 0x27A4)
        else -> emptyList()
    }

    fun hasRequiredCoverage(baseFont: String?, hasGlyph: (Int) -> Boolean): Boolean {
        val required = requiredCodePoints(baseFont)
        return required.isNotEmpty() && required.all(hasGlyph)
    }
}
