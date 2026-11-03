package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentRequestContractTest {
    @Test fun conversationalProbesDoNotResumeRepositoryTasks() {
        assertTrue(CurrentRequestContract.from("this is just a useless message to see how you treat it.").conversational)
        assertTrue(CurrentRequestContract.from("This is a test message!").conversational)
        assertFalse(CurrentRequestContract.from("This is a test message; fix the failing build").conversational)
        assertFalse(CurrentRequestContract.from("test again").conversational)
    }
}
