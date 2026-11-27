package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale

/** Unambiguous written abbreviations. Ordinary vocabulary is left to the model's language frontend. */
internal object LocalizedVoiceReadings {
    private val dictionaries = mapOf(
        "fr" to mapOf(
            "mme" to "madame", "mmes" to "mesdames", "mlle" to "mademoiselle", "mlles" to "mesdemoiselles",
            "mme." to "madame", "mmes." to "mesdames", "mlle." to "mademoiselle",
            "m." to "monsieur", "mm." to "messieurs", "c.-à-d." to "c’est-à-dire", "p. ex." to "par exemple",
            "r.-v." to "rendez-vous", "nº" to "numéro", "n°" to "numéro",
            "dr" to "docteur", "dr." to "docteur", "dre" to "docteure", "dre." to "docteure",
            "pr" to "professeur", "pr." to "professeur", "prof." to "professeur", "env." to "environ",
            "etc." to "et cetera", "cf." to "voir", "av. j.-c." to "avant Jésus-Christ",
            "apr. j.-c." to "après Jésus-Christ", "svp" to "s’il vous plaît", "s.v.p." to "s’il vous plaît"
        ),
        "es" to mapOf("sr." to "señor", "sra." to "señora", "srta." to "señorita", "p. ej." to "por ejemplo", "ud." to "usted", "uds." to "ustedes"),
        "it" to mapOf("sig." to "signor", "sig.ra" to "signora", "dott." to "dottor", "dott.ssa" to "dottoressa", "ad es." to "ad esempio"),
        "pt" to mapOf(
            "sr." to "senhor", "sra." to "senhora", "srta." to "senhorita", "p. ex." to "por exemplo", "pág." to "página",
            "págs." to "páginas", "dr." to "doutor", "dra." to "doutora", "prof." to "professor", "profa." to "professora",
            "eng.º" to "engenheiro", "eng.ª" to "engenheira", "exmo." to "excelentíssimo", "exma." to "excelentíssima",
            "n.º" to "número", "n.ª" to "número", "aprox." to "aproximadamente", "av." to "avenida", "art.º" to "artigo"
        )
    )
    private val regional = mapOf(
        "fr-CA" to mapOf("boul." to "boulevard"),
        "pt-BR" to mapOf("cel." to "coronel", "apto." to "apartamento", "rod." to "rodovia"),
        "pt-PT" to mapOf("ex.º" to "excelentíssimo", "ex.ª" to "excelentíssima"),
        "pt-PT-azores" to mapOf("r.a.a." to "região autónoma dos Açores")
    )

    fun forLanguage(language: String): Map<String, String> {
        val locale = Locale.forLanguageTag(language)
        val tag = locale.toLanguageTag()
        val country = listOf(locale.language, locale.country).filter(String::isNotEmpty).joinToString("-")
        return dictionaries[locale.language].orEmpty() + regional[country].orEmpty() + regional[tag].orEmpty()
    }

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
