package dev.chungjungsoo.gptmobile.data.voice

/** Unambiguous written abbreviations. Ordinary vocabulary is left to the model's language frontend. */
internal object LocalizedVoiceReadings {
    private val dictionaries = mapOf(
        "fr" to mapOf(
            "mme" to "madame", "mmes" to "mesdames", "mlle" to "mademoiselle", "mlles" to "mesdemoiselles",
            "mme." to "madame", "mmes." to "mesdames", "mlle." to "mademoiselle",
            "m." to "monsieur", "mm." to "messieurs", "c.-à-d." to "c’est-à-dire", "p. ex." to "par exemple",
            "r.-v." to "rendez-vous", "nº" to "numéro", "n°" to "numéro"
        ),
        "es" to mapOf("sr." to "señor", "sra." to "señora", "srta." to "señorita", "p. ej." to "por ejemplo", "ud." to "usted", "uds." to "ustedes"),
        "it" to mapOf("sig." to "signor", "sig.ra" to "signora", "dott." to "dottor", "dott.ssa" to "dottoressa", "ad es." to "ad esempio"),
        "pt" to mapOf("sr." to "senhor", "sra." to "senhora", "srta." to "senhorita", "p. ex." to "por exemplo", "pág." to "página")
    )
    fun forLanguage(language: String): Map<String, String> = dictionaries[language].orEmpty()

    fun numberSign(language: String) = when (language) {
        "en" -> "number "
        "fr" -> "numéro "
        "es", "pt" -> "número "
        "it" -> "numero "
        "hi" -> "नंबर "
        "zh" -> "编号"
        "ja" -> "番号"
        else -> "#"
    }
}
