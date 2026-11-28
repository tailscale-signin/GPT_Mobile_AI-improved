package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranslationLanguageDetectorTest {
    private val pair = listOf("en-US", "fr-FR")
    private fun prediction(language: String, confidence: Float) = VoiceTranslationLanguagePrediction(language, confidence)

    @Test fun aConfidentSourceReturnsTheSelectedLocaleForEitherDirection() {
        for ((language, selected) in listOf("en" to "en-US", "fr" to "fr-FR")) {
            val result = selectTranslationLanguage(listOf(prediction(language, 0.96f), prediction("de", 0.03f)), pair)
            assertEquals(VoiceTranslationLanguageStatus.DETECTED, result.status)
            assertEquals(selected, result.languageTag)
            assertEquals(0.96f, result.confidence)
        }
    }

    @Test fun aForeignWinnerCannotBecomeAnAllowedLanguageThroughFiltering() {
        val result = selectTranslationLanguage(listOf(prediction("de", 0.95f), prediction("en", 0.04f), prediction("fr", 0.01f)), pair)
        assertEquals(VoiceTranslationLanguageStatus.OUTSIDE_PAIR, result.status)
        assertNull(result.languageTag)
    }

    @Test fun lowConfidenceCloseScoresAndMissingLanguageEvidenceStayUnresolved() {
        val cases = listOf(
            listOf(prediction("en", 0.79f)) to "low_confidence",
            listOf(prediction("en", 0.85f), prediction("fr", 0.75f)) to "close_scores",
            listOf(prediction("und", 0.99f)) to "unknown_language",
            emptyList<VoiceTranslationLanguagePrediction>() to "no_prediction",
            listOf(prediction("en", Float.NaN)) to "invalid_scores",
            listOf(prediction("en", 1.1f)) to "invalid_scores"
        )
        for ((predictions, reason) in cases) {
            val result = selectTranslationLanguage(predictions, pair)
            assertEquals(reason, VoiceTranslationLanguageStatus.AMBIGUOUS, result.status)
            assertEquals(reason, result.reason)
            assertNull(result.languageTag)
        }
    }

    @Test fun regionalAliasesOfTheSamePredictedLanguageAreNotCompetingSources() {
        val result = selectTranslationLanguage(listOf(prediction("fr", 0.96f), prediction("fr-CA", 0.92f), prediction("en", 0.01f)), pair)
        assertEquals("fr-FR", result.languageTag)
    }

    @Test fun invalidPairsAndInsufficientOrOversizedTextNeverInitializeTheModel() = runBlocking {
        val creations = AtomicInteger()
        val detector = VoiceTranslationLanguageDetector(createBackend = {
            creations.incrementAndGet()
            throw AssertionError("No native task should be created")
        })
        try {
            assertEquals("invalid_pair", detector.detect("Bonjour tout le monde", listOf("en-US", "en-GB")).reason)
            assertEquals("invalid_pair", detector.detect("Bonjour tout le monde", listOf("en_US", "fr-FR")).reason)
            assertEquals("insufficient_text", detector.detect("42?!", pair).reason)
            assertEquals("insufficient_text", detector.detect("OK", pair).reason)
            assertEquals("text_too_long", detector.detect("a".repeat(8_001), pair).reason)
            assertEquals(0, creations.get())
        } finally {
            detector.close()
        }
    }

    @Test fun exactTextInitializationInferenceAndCloseStayOnOneWorker() = runBlocking {
        val threads = mutableListOf<Thread>()
        val sources = mutableListOf<String>()
        val worker = Executors.newSingleThreadExecutor()
        val caller = Thread.currentThread()
        var creations = 0
        var closes = 0
        val detector = VoiceTranslationLanguageDetector(createBackend = {
            creations++
            threads += Thread.currentThread()
            object : VoiceTranslationLanguageBackend {
                override fun detect(text: String): List<VoiceTranslationLanguagePrediction> {
                    threads += Thread.currentThread()
                    sources += text
                    return listOf(prediction("fr", 0.99f))
                }
                override fun close() {
                    closes++
                    threads += Thread.currentThread()
                }
            }
        }, worker = worker)
        val text = "  Pouvez-vous traduire cette question ?\n"
        assertEquals("fr-FR", detector.detect(text, pair).languageTag)
        assertEquals("fr-FR", detector.detect(text, pair).languageTag)
        detector.close()
        detector.close()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(text, text), sources)
        assertEquals(1, creations)
        assertEquals(1, closes)
        assertEquals(1, threads.distinct().size)
        assertFalse(threads.contains(caller))
        assertEquals("closed", detector.detect(text, pair).reason)
    }

    @Test fun unavailableNativeModelPreservesAnUnresolvedResultWithoutUsingAnotherDetector() = runBlocking {
        for (failure in listOf(IllegalStateException("missing asset"), UnsatisfiedLinkError("native unavailable"))) {
            val detector = VoiceTranslationLanguageDetector(createBackend = { throw failure })
            try {
                val result = detector.detect("Could you help me?", pair)
                assertEquals(VoiceTranslationLanguageStatus.UNAVAILABLE, result.status)
                assertEquals("model_unavailable", result.reason)
                assertNull(result.languageTag)
            } finally {
                detector.close()
            }
        }
    }

    @Test fun closingDuringInferenceRevokesItsResultAndClosesAfterNativeWork() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var closes = 0
        val detector = VoiceTranslationLanguageDetector(createBackend = {
            object : VoiceTranslationLanguageBackend {
                override fun detect(text: String): List<VoiceTranslationLanguagePrediction> {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    assertEquals(0, closes)
                    return listOf(prediction("en", 0.99f))
                }
                override fun close() {
                    closes++
                }
            }
        }, worker = worker)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { detector.detect("Please translate this sentence.", pair) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        detector.close()
        release.countDown()
        assertEquals("closed", pending.await().reason)
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, closes)
    }

    @Test fun cancellingPreparationDoesNotPublishTheLateLanguageResult() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var published = false
        val detector = VoiceTranslationLanguageDetector(createBackend = {
            object : VoiceTranslationLanguageBackend {
                override fun detect(text: String): List<VoiceTranslationLanguagePrediction> {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return listOf(prediction("fr", 0.99f))
                }
                override fun close() = Unit
            }
        }, worker = worker)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            detector.detect("Je voudrais une traduction.", pair)
            published = true
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        release.countDown()
        detector.close()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(pending.isCancelled)
        assertFalse(published)
    }
}
