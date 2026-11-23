package dev.chungjungsoo.gptmobile.data.voice

internal fun speechReferenceFixture(phrase: String, units: Long = 98): VoiceSpeechAudioObservation = VoiceSpeechAudioObservation(
    VoiceSpeechWorkload.digest(phrase),
    phrase.toByteArray(Charsets.UTF_8).size,
    (units * 600).toInt(),
    List(43) { if (it == 0) units - 42 else 1L },
    .1,
    .2,
    0,
    units * 600
)
