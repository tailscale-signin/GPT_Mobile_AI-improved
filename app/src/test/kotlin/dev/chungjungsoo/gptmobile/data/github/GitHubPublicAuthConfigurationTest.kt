package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubPublicAuthConfigurationTest {
    @Test fun installationOnboardingRequiresTheSameRegisteredClient() {
        val configuration = GitHubPublicAuthConfiguration("Iv1.project", "", "", "gpt-mobile-ai-workspace")
        assertEquals("https://github.com/apps/gpt-mobile-ai-workspace", configuration.installationManagementUrl("Iv1.project"))
        for (client in listOf(null, "", "Iv1.other")) {
            assertEquals("https://github.com/settings/installations", configuration.installationManagementUrl(client))
        }
    }

    @Test fun invalidOrUnconfiguredAppSlugsCannotChangeTheDestination() {
        for (slug in listOf("", "../settings", "app?query", "app#fragment", "app/other", "app.example", "-app", "a".repeat(101))) {
            val configuration = GitHubPublicAuthConfiguration("Iv1.project", "", "", slug)
            assertEquals("https://github.com/settings/installations", configuration.installationManagementUrl("Iv1.project"))
        }
        val unconfigured = GitHubPublicAuthConfiguration("", "", "", "gpt-mobile-ai-workspace")
        assertEquals("https://github.com/settings/installations", unconfigured.installationManagementUrl(""))
    }
}
