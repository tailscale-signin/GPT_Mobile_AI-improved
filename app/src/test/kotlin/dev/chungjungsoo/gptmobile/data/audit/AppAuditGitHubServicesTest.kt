package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditGitHubServicesTest {
    private val connection = ToolConnection("connection", "GitHub", "github", "GITHUB", "https://api.github.com", "OAUTH", "secret", "client")
    private val session = GitHubPublicAuthCoordinator.Session(
        GitHubPublicAuthCoordinator.Account("7", "private-login"),
        GitHubPublicAuthCoordinator.fingerprint("fixture-token"),
        "client",
        expiresAt = 120_000
    )

    @Test fun existingCurrentAuthorizationIsUsedWithoutRotatingCredentials() {
        assertTrue(appAuditGitHubCredentialReady(connection, "fixture-token", session, 1))
        assertFalse(appAuditGitHubCredentialReady(connection, "fixture-token", session, 60_000))
        assertFalse(appAuditGitHubCredentialReady(connection, "another-token", session, 1))
        assertFalse(appAuditGitHubCredentialReady(connection, "fixture-token", session.copy(verifyAfterRestore = true), 1))
        assertFalse(appAuditGitHubCredentialReady(connection, "fixture-token", session.copy(refreshState = "REFRESHING"), 1))
        assertFalse(appAuditGitHubCredentialReady(connection, "fixture-token", session.copy(expiresAt = Long.MIN_VALUE), 1))
        assertFalse(appAuditGitHubCredentialReady(connection.copy(authType = "BEARER"), "fixture-token", session.copy(refreshToken = "fixture-refresh"), 1))
    }

    @Test fun missingOAuthVerificationDoesNotBecomeAnAnonymousOrReplacementAccountRead() {
        assertFalse(appAuditGitHubCredentialReady(connection, "fixture-token", null, 1))
        assertFalse(appAuditGitHubCredentialReady(connection.copy(oauthClientId = "other"), "fixture-token", session, 1))
        assertFalse(appAuditGitHubCredentialReady(connection, null, session, 1))
        assertTrue(appAuditGitHubCredentialReady(connection.copy(authType = "BEARER"), "fixture-token", null, 1))
    }
}
