package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.speech.SpeechRecognizer

/** The platform service is checked before binding and is never silently switched to online speech. */
internal interface RecognitionService {
    fun available(onDevice: Boolean): Boolean
    fun create(onDevice: Boolean): SpeechRecognizer
}

internal class AndroidRecognitionService(private val context: Context) : RecognitionService {
    override fun available(onDevice: Boolean): Boolean = if (onDevice) {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } else {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun create(onDevice: Boolean): SpeechRecognizer = if (onDevice) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
        SpeechRecognizer.createSpeechRecognizer(context)
    }
}
