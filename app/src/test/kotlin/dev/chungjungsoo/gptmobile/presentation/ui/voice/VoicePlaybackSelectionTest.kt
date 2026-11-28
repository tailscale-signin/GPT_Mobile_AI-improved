package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackSelectionTest {
    @Test fun retainedFrenchOutputSurvivesDefaultAndRestoredOrdinarySettingsTogether() {
        val translated = factory("multilingual-estelle")
        val retained = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), translated)
        val ordinaryStates = listOf(
            factory("initial-default-pocket") to requireNotNull(MediaPipeSpeechVoices.forVoice("alba")),
            factory("restored-pocket-marius") to requireNotNull(MediaPipeSpeechVoices.forVoice("marius")),
            factory("changed-ordinary-spanish") to requireNotNull(MediaPipeSpeechVoices.forVoice("lola"))
        )
        for ((ordinaryFactory, ordinaryProfile) in ordinaryStates) {
            val selected = selectVoicePlayback(ordinaryFactory, ordinaryProfile, retained)
            assertSame(translated, selected.factory)
            assertEquals("fr-FR", selected.profile.tag)
            assertTrue(selected.requireLocalOutput)
        }
    }

    @Test fun explicitOwnerClearingRestoresAllOrdinaryOutputSettings() {
        val ordinary = factory("ordinary-pocket")
        val profile = requireNotNull(MediaPipeSpeechVoices.forVoice("marius"))
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), factory("multilingual-estelle"))
        assertTrue(selectVoicePlayback(ordinary, profile, accepted).requireLocalOutput)
        val cleared = selectVoicePlayback(ordinary, profile, null)
        assertSame(ordinary, cleared.factory)
        assertSame(profile, cleared.profile)
        assertFalse(cleared.requireLocalOutput)
    }

    @Test fun retainedEnglishVoiceDoesNotFollowTheFutureOrdinaryFrenchSelection() {
        val acceptedFactory = factory("multilingual-eponine")
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("fr-FR", "en-US", "eponine"), acceptedFactory)
        val selected = selectVoicePlayback(factory("ordinary-french"), requireNotNull(MediaPipeSpeechVoices.forVoice("estelle")), accepted)
        assertSame(acceptedFactory, selected.factory)
        assertEquals("eponine", accepted.target.voiceId)
        assertEquals("en-US", selected.profile.tag)
        assertTrue(selected.requireLocalOutput)
    }

    @Test fun explicitFactorySettingsChangesDoNotReplayThroughThePreviousFactory() {
        val ordinary = factory("ordinary-pocket")
        val profile = requireNotNull(MediaPipeSpeechVoices.forVoice("alba"))
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), factory("accepted-old-settings"))
        val previous = VoiceTranslationFactorySettings(1f, MediaPipeKokoroBucket.AUTO, true)
        for (next in listOf(previous.copy(rate = 1.25f), previous.copy(bucket = MediaPipeKokoroBucket.TOKENS_128), previous.copy(incrementalInput = false))) {
            val retained = translationPlaybackAfterSettingsChange(accepted, previous, next)
            val afterStop = selectVoicePlayback(ordinary, profile, retained)
            assertSame("The stopped reply must not keep its old factory for $next", ordinary, afterStop.factory)
            assertFalse(afterStop.requireLocalOutput)

            val replacement = factory("new-settings-$next")
            val replay = selectVoicePlayback(ordinary, profile, accepted.copy(factory = replacement))
            assertSame(replacement, replay.factory)
            assertEquals("fr-FR", replay.profile.tag)
            assertTrue(replay.requireLocalOutput)
        }
    }

    @Test fun unchangedExplicitSettingsKeepTheAcceptedFactoryAndLanguage() {
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), factory("accepted-current-settings"))
        val settings = VoiceTranslationFactorySettings(1.25f, MediaPipeKokoroBucket.TOKENS_128, false)
        val retained = translationPlaybackAfterSettingsChange(accepted, settings, settings.copy())
        assertSame(accepted, retained)
        val selected = selectVoicePlayback(factory("ordinary"), requireNotNull(MediaPipeSpeechVoices.forVoice("alba")), retained)
        assertSame(accepted.factory, selected.factory)
        assertEquals("fr-FR", selected.profile.tag)
        assertTrue(selected.requireLocalOutput)
        val warmCache = VoiceTranslationFactoryCache(factories = mapOf("fr-FR:estelle" to accepted.factory))
        assertSame(warmCache, warmCache.afterSettingsChange(settings, settings.copy()))
        assertSame(accepted.factory, warmCache["fr-FR:estelle"])
    }

    @Test fun changedFactorySettingsImmediatelyInvalidateEveryWarmFactory() {
        val settings = VoiceTranslationFactorySettings(1f, MediaPipeKokoroBucket.AUTO, true)
        val warmCache = VoiceTranslationFactoryCache(factories = mapOf("fr-FR:estelle" to factory("old-french"), "en-US:azelma" to factory("old-english")))
        val pair = VoiceTranslationPair()
        for (next in listOf(settings.copy(rate = 1.25f), settings.copy(bucket = MediaPipeKokoroBucket.TOKENS_128), settings.copy(incrementalInput = false))) {
            val invalidated = warmCache.afterSettingsChange(settings, next)
            assertNull("A replay or draft must not find a warm French factory for $next", invalidated["fr-FR:estelle"])
            assertNull(invalidated["en-US:azelma"])
            assertEquals(warmCache.generation + 1, invalidated.generation)

            val replacement = factory("new-french-$next")
            val ready = invalidated.publish(invalidated.preparation(next, pair), next, pair, mapOf("fr-FR:estelle" to replacement))
            assertSame(replacement, ready["fr-FR:estelle"])
        }
    }

    @Test fun latePreparationCannotRestoreTheCacheAfterSettingsChanged() {
        val settings = VoiceTranslationFactorySettings(1f, MediaPipeKokoroBucket.AUTO, true)
        val next = settings.copy(rate = 1.25f)
        val pair = VoiceTranslationPair()
        val cache = VoiceTranslationFactoryCache()
        val oldPreparation = cache.preparation(settings, pair)
        val invalidated = cache.afterSettingsChange(settings, next)
        val stale = invalidated.publish(oldPreparation, next, pair, mapOf("fr-FR:estelle" to factory("old-french")))
        assertSame(invalidated, stale)
        assertNull(stale["fr-FR:estelle"])
    }

    @Test fun latePreparationCannotRestoreTheCacheAfterSettingsChangeBack() {
        val settings = VoiceTranslationFactorySettings(1f, MediaPipeKokoroBucket.AUTO, true)
        val changed = settings.copy(incrementalInput = false)
        val pair = VoiceTranslationPair()
        val cache = VoiceTranslationFactoryCache()
        val oldPreparation = cache.preparation(settings, pair)
        val restored = cache.afterSettingsChange(settings, changed).afterSettingsChange(changed, settings)
        val stale = restored.publish(oldPreparation, settings, pair, mapOf("fr-FR:estelle" to factory("old-french")))
        assertSame(restored, stale)
        assertNull(stale["fr-FR:estelle"])
        val replacement = factory("fresh-french")
        val ready = restored.publish(restored.preparation(settings, pair), settings, pair, mapOf("fr-FR:estelle" to replacement))
        assertSame(replacement, ready["fr-FR:estelle"])
    }

    @Test fun preparationMustMatchTheCurrentFactoryConfigurationAndLanguagePair() {
        val settings = VoiceTranslationFactorySettings(1f, MediaPipeKokoroBucket.AUTO, true)
        val pair = VoiceTranslationPair()
        val cache = VoiceTranslationFactoryCache()
        val request = cache.preparation(settings, pair)
        val prepared = mapOf("fr-FR:estelle" to factory("prepared-french"))
        assertSame(cache, cache.publish(request, settings.copy(bucket = MediaPipeKokoroBucket.TOKENS_128), pair, prepared))
        assertSame(cache, cache.publish(request, settings, pair.copy(sourceLanguage = "en-US"), prepared))
        assertSame(cache, cache.publish(request, settings, null, prepared))
    }

    private fun factory(name: String) = object : VoicePhraseEngineFactory {
        override val identity = name
        override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Selection must not open a speech engine")
    }
}
