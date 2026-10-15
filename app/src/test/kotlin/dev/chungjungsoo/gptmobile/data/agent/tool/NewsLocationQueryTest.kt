package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsLocationQueryTest {
    @Test fun locationSupportsCityNamesWithoutChangingCountrySettings() {
        assertEquals("Toronto", newsLocationQuery("location", null, " Toronto "))
        assertEquals("transit Toronto", newsLocationQuery("search", "transit", "Toronto"))
        assertEquals("Toronto transit", newsLocationQuery("search", "Toronto transit", "Toronto"))
        assertEquals("AI", newsLocationQuery("topic", "AI", "Toronto"))
    }

    @Test fun invalidLocationIsRejectedBeforeNetworkExecution() {
        assertTrue(runCatching { newsLocationQuery("location", null, " ") }.isFailure)
        assertTrue(runCatching { newsLocationQuery("search", "x".repeat(250), "Toronto") }.isFailure)
    }
}
