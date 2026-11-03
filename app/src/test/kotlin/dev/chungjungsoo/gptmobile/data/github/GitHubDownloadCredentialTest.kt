package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDownloadCredentialTest {
    @Test fun refreshResultNeverReadsOldVaultReference() = runTest {
        var reads = 0
        val token = gitHubDownloadCredential({ "fresh credential" }, {
            reads++
            error("old reference no longer exists")
        })
        assertEquals("fresh credential", token)
        assertEquals(0, reads)
    }

    @Test fun legacyCredentialIsWipedAfterUse() = runTest {
        val bytes = "credential".encodeToByteArray()
        assertEquals("credential", gitHubDownloadCredential(null) { bytes })
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun failedRefreshNeverFallsBackToOldCredential() = runTest {
        var reads = 0
        val result = runCatching {
            gitHubDownloadCredential({ error("refresh rejected") }, {
                reads++
                byteArrayOf()
            })
        }
        assertTrue(result.isFailure)
        assertEquals(0, reads)
    }
}
