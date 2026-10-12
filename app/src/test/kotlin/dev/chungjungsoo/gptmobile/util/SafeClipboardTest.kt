package dev.chungjungsoo.gptmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeClipboardTest {
    @Test fun keepsOrdinaryTextAndRoutesOversizedTextToFiles() {
        assertFalse(SafeClipboard.requiresFile(32_768))
        assertTrue(SafeClipboard.requiresFile(32_769))
        assertTrue(SafeClipboard.requiresFile(Int.MAX_VALUE))
    }
}
