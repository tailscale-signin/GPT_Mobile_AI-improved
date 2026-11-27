package dev.chungjungsoo.gptmobile.data.voice

/**
 * Voices documented by the immutable model revisions in [MediaPipeSpeechCatalog].
 * This is a selection catalog, not synthesis qualification: the opened task still checks
 * listVoices() before accepting a voice, and successful PCM evidence stays device-specific.
 */
internal object MediaPipeSpeechVoices {
    private val pocketEnglish = SpeechLanguage(
        "en-US",
        "Hello. This is your selected voice. I am ready when you are.",
        "alba marius javert jean fantine cosette eponine azelma".split(" ")
    )
    private val phononEnglish = SpeechLanguage(
        "en-US",
        pocketEnglish.preview,
        "freya claire lukas sofia lucas".split(" ")
    )
    private val pocketLanguages = listOf(
        pocketEnglish,
        SpeechLanguage("fr-FR", "Bonjour. Voici la voix que vous avez choisie.", listOf("estelle")),
        SpeechLanguage("de-DE", "Hallo. Das ist deine ausgewählte Stimme.", listOf("juergen")),
        SpeechLanguage("es-ES", "Hola. Esta es la voz que has elegido.", listOf("lola")),
        SpeechLanguage("it-IT", "Ciao. Questa è la voce che hai scelto.", listOf("giovanni")),
        SpeechLanguage("pt-BR", "Olá. Esta é a voz que você escolheu.", listOf("rafael")),
        SpeechLanguage("nl-NL", "Hallo. Dit is de stem die je hebt gekozen.", listOf("daan"))
    )

    fun profiles(engine: MediaPipeSpeechEngine): List<SpeechLanguage> = when (engine) {
        MediaPipeSpeechEngine.PHONON -> listOf(phononEnglish)
        MediaPipeSpeechEngine.POCKET -> listOf(pocketEnglish)
        MediaPipeSpeechEngine.POCKET_MULTILINGUAL -> pocketLanguages
        else -> SpeechLanguages.synthesis
    }

    fun ids(engine: MediaPipeSpeechEngine): List<String> = profiles(engine).flatMap { it.voices }

    fun profile(engine: MediaPipeSpeechEngine, voiceId: String): SpeechLanguage? = profiles(engine).firstOrNull { voiceId in it.voices }

    fun forVoice(voiceId: String): SpeechLanguage? = (pocketLanguages + phononEnglish).firstOrNull { voiceId in it.voices }

    fun selected(engine: MediaPipeSpeechEngine, requested: String?): String =
        requested?.takeIf { profile(engine, it) != null } ?: MediaPipeSpeechCatalog.model(engine).defaultVoice

    fun restore(saved: Set<*>?): Map<String, String> = buildMap {
        saved?.filterIsInstance<String>()?.forEach { entry ->
            val engine = MediaPipeSpeechEngine.entries.firstOrNull { !it.isKokoro && it.name == entry.substringBefore('=') }
            val voice = entry.substringAfter('=', "")
            if (engine != null && profile(engine, voice) != null) put(engine.name, voice)
        }
    }

    fun choices(engine: MediaPipeSpeechEngine): List<Pair<String, String>> = profiles(engine).flatMap { language ->
        language.voices.map { id -> id to "${id.replaceFirstChar(Char::uppercaseChar)} · ${language.label}" }
    }

    /** Voice-source terms are separate from the model-weight license. */
    fun attribution(voiceId: String): String? = when (voiceId) {
        "alba" -> "Alba MacKenna voice · CC BY 4.0"
        "marius", "javert" -> "Kyutai voice donation · CC0"
        "fantine", "eponine", "azelma" -> "VCTK voice · CC BY 4.0"
        "cosette" -> "Expresso voice · CC BY-NC 4.0 · non-commercial use"
        "jean" -> "EARS voice · CC BY-NC 4.0 · non-commercial use"
        "estelle" -> "Kyutai Unmute recording · CC0"
        else -> null
    }

    fun processorAvailability(engine: MediaPipeSpeechEngine): String? = when (engine) {
        MediaPipeSpeechEngine.POCKET, MediaPipeSpeechEngine.POCKET_MULTILINGUAL ->
            "Pocket runs on CPU in this speech runtime. GPU and NPU are unavailable for this pack; Kokoro offers accelerator options."
        MediaPipeSpeechEngine.PHONON -> "Phonon runs on CPU in this speech runtime. GPU and NPU are unavailable for this pack."
        else -> null
    }
}
