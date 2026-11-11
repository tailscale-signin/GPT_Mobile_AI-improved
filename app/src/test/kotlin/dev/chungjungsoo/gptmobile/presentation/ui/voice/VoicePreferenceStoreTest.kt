package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoicePreferenceStoreTest {
    @Test fun malformedFieldsDoNotDiscardValidChoicesOrGrantOnlineSpeech() {
        val value = readVoicePreferences(
            mapOf(
                "online" to "true", "captions" to false, "rate" to Float.NaN, "kokoro_threads" to "4",
                "language" to "en-CA", "review" to true, "voice" to 12, "automatic" to "false",
                "recognition_backend" to "UNKNOWN", "kokoro_voice" to "EMMA", "spoken_replies" to false
            )
        )
        assertFalse(value.online)
        assertFalse(value.captions)
        assertFalse(value.spokenReplies)
        assertEquals(1f, value.rate)
        assertEquals(2, value.kokoroThreads)
        assertEquals("en-CA", value.language)
        assertEquals("", value.voice)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, value.recognitionBackend)
        assertEquals(KokoroVoice.EMMA, value.kokoroVoice)
        assertEquals(true, value.review)
    }

    @Test fun screenDisposalBeforeWriterRunsStillSavesLastSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val views = ViewModelStore()
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val preferences = context.getSharedPreferences("voice-writer-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val store = VoicePreferenceStore({ preferences }, backgroundScope)
            val model = VoicePreferencesViewModel(context, store)
            views.put("voice", model)
            model.save(VoicePreferences(spokenReplies = true, kokoroThreads = 2))
            val expected = VoicePreferences(spokenReplies = false, online = false, kokoroVoice = KokoroVoice.HEART, kokoroThreads = 4)
            model.save(expected)
            views.clear()
            runCurrent()
            assertEquals(expected, readVoicePreferences(preferences.all))
            assertEquals(expected, store.preferences.value)
            assertNull(store.saveError.value)
        } finally {
            views.clear()
            Dispatchers.resetMain()
        }
    }
}
