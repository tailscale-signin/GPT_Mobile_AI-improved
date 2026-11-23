package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Locale
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidVoicePrivacyTest {
    @Test fun deferredSpeechKeepsItsOriginalConversationPolicyWhenAnotherRoomBecomesCurrent() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        for (privateReply in listOf(true, false)) {
            val work = java.util.ArrayDeque<Runnable>()
            var initialized: TextToSpeech.OnInitListener? = null
            val engine = mockk<TextToSpeech>(relaxed = true)
            val online = Voice("online", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
            every { engine.voices } returns setOf(online)
            every { engine.voice } returns online
            val audio = AndroidVoiceAudio(context, Executor { work.add(it) }) { _, callback ->
                initialized = callback
                engine
            }
            var current = ConversationPrivacyContext(1, privateReply)
            audio.bindConversationPrivacy { current }
            audio.allowOnlineSpeech = true
            audio.languageTag = "en-US"
            val failures = mutableListOf<String>()
            fun drain() {
                val worker = Thread { while (work.isNotEmpty()) work.removeFirst().run() }
                worker.start()
                worker.join(3000)
                assertTrue(!worker.isAlive)
            }
            try {
                assertEquals(if (privateReply) "On device speech only" else "System speech service may use the network", audio.processingLocation)
                audio.speak("Original reply", {}, failures::add)
                current = ConversationPrivacyContext(2, !privateReply)
                requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
                shadowOf(Looper.getMainLooper()).idle()
                drain()
                shadowOf(Looper.getMainLooper()).idle()
                if (privateReply) {
                    verify(exactly = 0) { engine.speak(any<CharSequence>(), any(), any(), any()) }
                    assertTrue(failures.single().contains("offline voice"))
                } else {
                    verify(exactly = 1) { engine.speak("Original reply", TextToSpeech.QUEUE_ADD, null, any()) }
                    assertTrue(failures.isEmpty())
                }
            } finally {
                audio.close()
                drain()
            }
        }
    }

    @Test fun delayedRecognitionSupportRetainsItsOriginalConversationPolicy() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (privateCapture in listOf(true, false)) {
            val service = DeferredRecognition()
            val audio = AndroidVoiceAudio(context, Executor { it.run() }, { _, _ -> mockk(relaxed = true) }, service, RecognitionStartGate { 0L })
            val original = ConversationPrivacyContext(1, privateCapture)
            var current = original
            audio.bindConversationPrivacy { current }
            audio.allowOnlineSpeech = true
            val observed = mutableListOf<ConversationPrivacyContext?>()
            every { service.engine.startListening(any()) } answers {
                observed += ConversationPrivacyContext.currentOrNull()
                Unit
            }
            val failures = mutableListOf<String>()
            val listener = object : VoiceAudio.Listener {
                override fun ready() = Unit
                override fun level(value: Float) = Unit
                override fun partial(text: String) = Unit
                override fun final(text: String) = Unit
                override fun error(message: String) {
                    failures += message
                }
            }
            try {
                audio.listen(listener)
                assertEquals(listOf(privateCapture), service.modes)
                current = ConversationPrivacyContext(2, !privateCapture)
                ConversationPrivacyContext.withCaptured(current) {
                    requireNotNull(service.pending)(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
                    assertSame(current, ConversationPrivacyContext.currentOrNull())
                }
                assertEquals(listOf(original), observed)
                assertTrue(failures.isEmpty())
            } finally {
                audio.close()
            }
        }
    }

    private class DeferredRecognition : RecognitionService {
        val engine = mockk<SpeechRecognizer>(relaxed = true)
        val modes = mutableListOf<Boolean>()
        var pending: ((RecognitionQualification) -> Unit)? = null
        override fun available(onDevice: Boolean): Boolean = true.also { modes += onDevice }
        override fun create(onDevice: Boolean): SpeechRecognizer = engine
        override fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) {
            pending = result
        }
    }
}
