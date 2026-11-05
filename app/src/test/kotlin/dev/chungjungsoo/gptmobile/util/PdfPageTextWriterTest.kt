package dev.chungjungsoo.gptmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageTextWriterTest {
    @Test fun anExactFitRetainsBothCompletePagesAndContinuesAtTheNextPage() {
        val output = PdfPageTextWriter(1, 3, 13)
        output.beginPage(1)
        output.write("First!")
        output.endPage(1)
        output.beginPage(2)
        output.write("Second")
        output.endPage(2)
        assertThrows(PdfPageTextWriter.LimitReached::class.java) { output.beginPage(3) }
        assertEquals("First!\nSecond", output.toString())
        assertEquals(2, output.lastPage)
        assertEquals(3, output.nextPage)
        assertFalse(output.partialPage)
    }

    @Test fun overflowHalfwayThroughALaterPageRollsBackThatWholePage() {
        val output = PdfPageTextWriter(1, 3, 16)
        output.beginPage(1)
        output.write("complete")
        output.endPage(1)
        output.beginPage(2)
        output.write("left")
        assertThrows(PdfPageTextWriter.LimitReached::class.java) { output.write("right") }
        assertEquals("complete", output.toString())
        assertEquals(1, output.lastPage)
        assertEquals(2, output.nextPage)
        assertFalse(output.partialPage)
    }

    @Test fun oversizedFirstPageIsExplicitlyPartialAndCannotSplitAUnicodeCharacter() {
        val output = PdfPageTextWriter(4, 5, 4)
        output.beginPage(4)
        assertThrows(PdfPageTextWriter.LimitReached::class.java) { output.write("abc😀remainder") }
        assertEquals("abc", output.toString())
        assertTrue(output.partialPage)
        assertEquals(4, output.lastPage)
        assertEquals(null, output.nextPage)
    }

    @Test fun emptyPagesCannotPreventAPartialTextPageOrStallTheNextRange() {
        val output = PdfPageTextWriter(1, 5, 8)
        output.beginPage(2)
        output.write(" ")
        output.endPage(2)
        output.beginPage(4)
        assertThrows(PdfPageTextWriter.LimitReached::class.java) { output.write("meaningful content") }
        assertTrue(output.toString().contains("mean"))
        assertTrue(output.partialPage)
        assertEquals(4, output.lastPage)
        val complete = PdfPageTextWriter(2, 5, 20)
        complete.beginPage(3)
        complete.write("evidence")
        complete.endPage(3)
        complete.completeRange()
        assertEquals(5, complete.lastPage)
        assertEquals(null, complete.nextPage)
    }

    @Test fun cancellationInterruptsWorkWithoutContinuingToAnotherPage() {
        val output = PdfPageTextWriter(1, 2, 100)
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.io.InterruptedIOException::class.java) { output.beginPage(1) }
        } finally {
            Thread.interrupted()
        }
        assertEquals("", output.toString())
    }
}
