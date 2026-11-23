package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.collection.LruCache
import androidx.compose.ui.text.AnnotatedString
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cache revocation is also called by plain-JVM diagnostics consumers, without an Android runtime. */
class ChatRenderingCacheBoundaryTest {
    @Test
    fun disablingDiagnosticsClearsAllRenderingCachesAndRevokesTheCaptureEpoch() = withCleanCaches {
        LocalDiagnosticsPolicy.setEnabled(true)
        val epoch = ReportDiagnosticSnapshots.originEpoch.value
        val source = "Public **rendering** cache fixture."
        val parsed = parseChatMarkdown(source)
        val renderer = requireNotNull(settledMarkdownRendererCache.prepare(source, cacheable = true))
        val highlight = AnnotatedString("val publicFixture = 1")
        highlightCache().put("public-highlight", highlight)
        assertSame(parsed, parseChatMarkdown(source))
        assertSame(renderer, settledMarkdownRendererCache.prepare(source, cacheable = true))
        assertSame(highlight, highlightCache().get("public-highlight"))

        LocalDiagnosticsPolicy.setEnabled(false)

        assertFalse(LocalDiagnosticsPolicy.enabled)
        assertNotSame(epoch, ReportDiagnosticSnapshots.originEpoch.value)
        assertEquals(0, highlightCache().size())
        assertEquals(0, settledMarkdownRendererCache.retainedEntries())
        assertEquals(0, settledMarkdownRendererCache.retainedBytes())
        assertNotSame(parsed, parseChatMarkdown(source))
        assertNotSame(renderer, settledMarkdownRendererCache.prepare(source, cacheable = true))
        LocalDiagnosticsPolicy.setEnabled(true)
        assertNotSame(epoch, ReportDiagnosticSnapshots.originEpoch.value)
    }

    @Test
    fun boundedParseCacheEvictsTheLeastRecentlyUsedEntryAndBypassesProtectedReads() = withCleanCaches {
        // Each entry charges a little over 400 KiB, so five fit and six exceed the 2 MiB bound.
        val sources = (0..5).map { "$it:" + "p".repeat(100 * 1024) }
        val retained = sources.take(5).map(::parseChatMarkdown)
        assertSame(retained[0], parseChatMarkdown(sources[0]))
        parseChatMarkdown(sources[5])
        assertSame(retained[0], parseChatMarkdown(sources[0]))
        assertNotSame(retained[1], parseChatMarkdown(sources[1]))
        val current = parseChatMarkdown(sources[0])
        assertNotSame(current, parseChatMarkdown(sources[0], cacheResult = false))
        assertSame(current, parseChatMarkdown(sources[0]))
    }

    @Test
    fun concurrentDebugOffOnFencesAParseAlreadyInFlight() = withCleanCaches {
        LocalDiagnosticsPolicy.setEnabled(true)
        val source = "Public paragraph prepared concurrently. ".repeat(1000)
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val pending = worker.submit<ParsedChatMarkdown> {
            var checkpoints = 0
            parseChatMarkdown(source, checkActive = {
                if (++checkpoints == 3) {
                    parsing.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Parse gate was not released" }
                }
            })
        }
        try {
            assertTrue(parsing.await(5, TimeUnit.SECONDS))
            LocalDiagnosticsPolicy.setEnabled(false)
            LocalDiagnosticsPolicy.setEnabled(true)
            release.countDown()
            val beforeRevocation = pending.get(5, TimeUnit.SECONDS)
            val afterRevocation = parseChatMarkdown(source)
            assertNotSame(beforeRevocation, afterRevocation)
            assertSame(afterRevocation, parseChatMarkdown(source))
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private inline fun withCleanCaches(test: () -> Unit) {
        LocalDiagnosticsPolicy.setEnabled(false)
        try {
            test()
        } finally {
            LocalDiagnosticsPolicy.setEnabled(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun highlightCache(): LruCache<String, AnnotatedString> =
        Class.forName("dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatMarkdownKt")
            .getDeclaredField("codeHighlightCache")
            .apply { isAccessible = true }
            .get(null) as LruCache<String, AnnotatedString>
}
