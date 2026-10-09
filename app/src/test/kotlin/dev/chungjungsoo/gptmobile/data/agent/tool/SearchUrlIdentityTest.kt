package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchUrlIdentityTest {
    @Test fun trackingAndDefaultPortsCollapseWhileNavigationIsUntouched() {
        val original = "HTTPS://Example.ORG:443/a?%75tm_source=engine&fbclid=123&id=7&msclkid=456"
        assertEquals(canonicalSearchUrl("https://example.org/a?id=7"), canonicalSearchUrl(original))
        assertEquals(original, SearchUrlIdentity.parse(original)?.navigationUrl)
        assertEquals(canonicalSearchUrl("http://example.org:80"), canonicalSearchUrl("http://example.org/"))
    }

    @Test fun meaningfulResourceDifferencesArePreserved() {
        for ((first, second) in listOf(
            "http://example.org/a" to "https://example.org/a",
            "https://www.example.org/a" to "https://example.org/a",
            "https://example.org:8443/a" to "https://example.org/a",
            "https://example.org/a/" to "https://example.org/a",
            "https://example.org/Doc" to "https://example.org/doc",
            "https://example.org/a%2Fb" to "https://example.org/a/b",
            "https://example.org/a?page=1" to "https://example.org/a?page=2",
            "https://example.org/a?ref=one" to "https://example.org/a?ref=two",
            "https://example.org/a?b=2&a=1" to "https://example.org/a?a=1&b=2",
            "https://example.org/a?q=one&q=two" to "https://example.org/a?q=two&q=one",
            "https://example.org/a#section" to "https://example.org/a",
            "https://example.org/#/view/one" to "https://example.org/#/view/two",
            "https://example.org/a?signature=AbC" to "https://example.org/a?signature=abc"
        )) {
            assertNotEquals(first, canonicalSearchUrl(first), canonicalSearchUrl(second))
        }
    }

    @Test fun malformedAndCredentialLinksAreRejected() {
        for (value in listOf("/relative", "ftp://example.org/a", "javascript:alert(1)", "https://user:pass@example.org/a", "https://example.org/a%xy", "https://[broken", "https://example.org:70000/a", "https://example.org:bad/a", "https://example.org\\bad")) assertNull(value, SearchUrlIdentity.parse(value))
    }

    @Test fun unicodeHostUsesAnIdentityWithoutChangingTheLink() {
        val value = "https://bücher.example/Über"
        assertEquals("https://xn--bcher-kva.example/Über", canonicalSearchUrl(value))
        assertEquals(value, SearchUrlIdentity.parse(value)?.navigationUrl)
    }
}
