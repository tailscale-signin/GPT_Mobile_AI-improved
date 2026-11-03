package dev.chungjungsoo.gptmobile.data.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubTaskSubjectTest {
    @Test fun firstPromptProducesTaskSubjectWithoutRepositoryPrefix() {
        assertEquals("Fix voice retry", GitHubConversationSubject.taskSummary("Please fix voice retry"))
        assertEquals("Development task", GitHubConversationSubject.taskSummary("Please"))
    }
}
