package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubReadRetryPolicyTest {
    @Test fun retriesHonorCooldownAndNeverReplayWritesOrCancellation() = runTest {
        var attempts = 0
        GitHubReadRetry.run {
            if (++attempts == 1) throw GitHubReadRetry.HttpFailure(503, "7")
            "ok"
        }
        assertEquals(7000L, testScheduler.currentTime)
        for (failure in listOf(IOException("lost write"), SSLException("certificate"), CancellationException("stopped"), GitHubReadRetry.HttpFailure(401, null), GitHubReadRetry.HttpFailure(503, "90"))) {
            attempts = 0
            val error = runCatching {
                GitHubReadRetry.run(readOnly = failure.message != "lost write") {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertTrue(error === failure)
            assertEquals(1, attempts)
        }
        assertEquals(3000L, GitHubReadRetry.retryAfterMillis("Thu, 01 Jan 1970 00:00:03 GMT", 0))
    }
}
