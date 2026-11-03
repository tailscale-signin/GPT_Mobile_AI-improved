package dev.chungjungsoo.gptmobile.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshWindowTest {
    @Test fun dnsFailureBackoffIsBoundedAndNewRouteReopensRefresh() {
        val window = CatalogRefreshWindow()
        assertTrue(window.available(0, 1))
        window.completed(0, false)
        assertFalse(window.available(29_999, 1))
        assertTrue(window.available(30_000, 1))
        repeat(20) { window.completed(30_000, false) }
        assertFalse(window.available(329_999, 1))
        assertTrue(window.available(330_000, 1))
        window.completed(330_000, false)
        assertTrue(window.available(330_001, 2))
    }

    @Test fun successfulRefreshCoalescesRepeatedNavigation() {
        val window = CatalogRefreshWindow()
        assertTrue(window.available(10, 1))
        window.completed(10, true)
        assertFalse(window.available(100, 1))
        assertTrue(window.available(30_010, 1))
    }
}
