package dev.chungjungsoo.gptmobile.data.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderOutputLimitsTest {
    @Test fun explicitCeilingIsLearnedForOnlyThatRoute() {
        val route = "${java.util.UUID.randomUUID()}|mimo|GMICloud"
        assertEquals(131072, ProviderOutputLimits.learn(route, "max_tokens is too large: 256000. This model supports at most 131072 completion tokens, whereas you provided 256000.", 256000))
        assertEquals(131072, ProviderOutputLimits.effective(route, 256000))
        assertEquals(4096, ProviderOutputLimits.effective(route, 4096))
        assertNull(ProviderOutputLimits.effective("$route-other", null))
    }

    @Test fun contextErrorsAndUnrelatedNumbersNeverBecomeCompletionCeilings() {
        assertNull(ProviderOutputLimits.learn("context", "max_tokens is too large: 256000; context window 131072", 256000))
        assertNull(ProviderOutputLimits.learn("small", "max_tokens is too large; supports at most 131072 completion tokens", 4096))
        assertNull(ProviderOutputLimits.learn("quota", "429 retry after 131072 seconds", 256000))
    }
}
