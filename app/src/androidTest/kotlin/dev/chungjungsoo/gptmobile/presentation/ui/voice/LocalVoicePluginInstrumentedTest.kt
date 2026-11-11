package dev.chungjungsoo.gptmobile.presentation.ui.voice

import ai.moonshine.voice.JNI
import android.media.AudioAttributes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Packaged JNI/UI qualification; does not claim microphone or speech-model inference execution. */
class LocalVoicePluginInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun packagedNativeSpeechRuntimeMatchesItsJavaApi() {
        JNI.ensureLibraryLoaded()
        assertEquals(JNI.MOONSHINE_HEADER_VERSION, JNI.moonshineGetVersion())
    }

    @Test fun kokoroPlaybackClassVerifiesBeforeAnyModelIsInstalled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var created = false
        instrumentation.runOnMainSync {
            val models = KokoroModelPack.store(File(context.cacheDir, "voice-output-class-load"))
            val output = createKokoroVoiceOutput(context, models, { AudioAttributes.Builder().build() }, { 1f })
            created = output != null
            output?.close()
        }
        assertTrue("Android must load the packaged Kokoro playback class when Voice Chat opens", created)
    }

    @Test fun modelInstallRequiresExplicitAction() {
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                LocalVoiceSetupCard(LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED), { downloads++ }, {}, {})
            }
        }
        compose.onNodeWithTag("local_voice_setup").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithTag("download_local_voice").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun downloadCancellationAndModelRemovalHaveSeparateActions() {
        val state = mutableStateOf(LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING))
        var cancelled = 0
        var removed = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalVoiceSetupCard(state.value, {}, { cancelled++ }, { removed++ }, showRemove = true)
                }
            }
        }
        compose.onNodeWithText("Cancel download").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, removed)
            state.value = LocalVoiceModelState(LocalVoiceModelStatus.READY)
        }
        compose.onNodeWithText("Installed · Recognition works offline").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("download_local_voice").assertCountEquals(0)
        compose.onNodeWithText("Remove speech model").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
