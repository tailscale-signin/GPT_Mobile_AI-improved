package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleMenuPositionTest {
    @Test fun popupUsesTheSideOppositeTheThumbWithAVisibleGap() {
        val anchor = IntRect(10, 100, 990, 700)
        val window = IntSize(1000, 800)
        val menu = IntSize(224, 300)
        assertEquals(158, bubbleMenuOffset(anchor, IntOffset(100, 200), window, menu, 48, 12).x)
        assertEquals(638, bubbleMenuOffset(anchor, IntOffset(900, 200), window, menu, 48, 12).x)
    }

    @Test fun edgeAndScrolledAnchorsStayInsideTheWindow() {
        val window = IntSize(320, 240)
        val menu = IntSize(224, 200)
        for (y in listOf(-1000, 0, 120, 1000)) {
            for (x in listOf(-1000, 0, 160, 320, 1000)) {
                val offset = bubbleMenuOffset(IntRect(0, -300, 320, 1000), IntOffset(x, y), window, menu, 48, 12)
                assertTrue(offset.x in 12..84)
                assertTrue(offset.y in 12..28)
            }
        }
    }

    @Test fun largerThanWindowMenuDoesNotCreateInvalidClampRange() {
        assertEquals(IntOffset.Zero, bubbleMenuOffset(IntRect.Zero, IntOffset.Zero, IntSize(100, 100), IntSize(224, 300), 48, 12))
    }
}
