package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFixtureTestModeTest {
    @Test fun existingFixtureSelectionAdmitsOnlyReadOnlyCoverage() {
        val original = GitHubWorkflowPreset.selected("test_integration").prompt()
        val selected = GitHubFixtureTestMode.EXISTING_READ_ONLY.prompt(original)
        assertTrue(GitHubChecklistScope.readOnly(selected))
        assertFalse(GitHubChecklistScope.supportedFixturePlan(selected))
        assertFalse(GitHubWorkflowPreset.implementation(selected))
        assertTrue(selected.contains("OUT_OF_SCOPE"))
        assertTrue(selected.contains("Reuse completed read receipts"))
        assertEquals(GitHubWorkflowPreset.fromRequest(original), GitHubWorkflowPreset.fromRequest(selected))
    }

    @Test fun supportedFixturesKeepTheOriginalAuthorizedWorkflowAndExplainItsPolicy() {
        val original = GitHubWorkflowPreset.selected("test_integration").prompt()
        assertEquals(original, GitHubFixtureTestMode.SUPPORTED_FIXTURES.prompt(original))
        assertFalse(GitHubChecklistScope.readOnly(original))
        assertTrue(GitHubFixtureTestMode.SUPPORTED_FIXTURES.description.contains("General tool auto-approval does not approve fixture changes"))
    }
}
