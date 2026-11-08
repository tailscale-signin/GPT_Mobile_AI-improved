package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** The platform service is checked before binding and is never silently switched to online speech. */
internal interface RecognitionService {
    fun available(onDevice: Boolean): Boolean
    fun create(onDevice: Boolean): SpeechRecognizer
    fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) = result(RecognitionQualification(RecognitionSupportStatus.UNVERIFIED))
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

    override fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            result(RecognitionQualification(RecognitionSupportStatus.UNVERIFIED))
            return
        }
        recognizer.checkRecognitionSupport(
            intent,
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    result(
                        qualifyRecognitionLanguage(
                            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).orEmpty(),
                            onDevice,
                            support.installedOnDeviceLanguages,
                            support.pendingOnDeviceLanguages,
                            support.supportedOnDeviceLanguages,
                            support.onlineLanguages
                        )
                    )
                }
                override fun onError(error: Int) {
                    result(RecognitionQualification(if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) RecognitionSupportStatus.UNVERIFIED else RecognitionSupportStatus.FAILED, error))
                }
            }
        )
    }
}

internal enum class RecognitionSupportStatus { SUPPORTED, UNVERIFIED, LANGUAGE_UNAVAILABLE, UNSUPPORTED, FAILED }
internal data class RecognitionQualification(val status: RecognitionSupportStatus, val errorCode: Int? = null)

/** Match the requested locale without silently replacing it with another regional language. */
internal fun qualifyRecognitionLanguage(language: String, onDevice: Boolean, installed: List<String>, pending: List<String>, supported: List<String>, online: List<String>): RecognitionQualification {
    val requested = Locale.forLanguageTag(language).toLanguageTag()
    fun List<String>.containsRequested() = any { Locale.forLanguageTag(it).toLanguageTag().equals(requested, ignoreCase = true) }
    return when {
        requested == "und" -> RecognitionQualification(RecognitionSupportStatus.UNSUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        installed.containsRequested() || !onDevice && online.containsRequested() -> RecognitionQualification(RecognitionSupportStatus.SUPPORTED)
        pending.containsRequested() || supported.containsRequested() -> RecognitionQualification(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        else -> RecognitionQualification(RecognitionSupportStatus.UNSUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }
}
