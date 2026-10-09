package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestResponseBoundaryTest {
    @Test fun `manual drag and slow released momentum pass the response header`() {
        val boundary = LatestResponseBoundary()
        boundary.beginFling(2500f, 1000f)
        assertEquals(0f, boundary.consume(100f, 20f, 0, true))
        boundary.beginFling(400f, 1000f)
        assertEquals(0f, boundary.consume(100f, 20f, 0, false))
    }

    @Test fun `fast fling clips overshoot for one second`() {
        val boundary = LatestResponseBoundary()
        boundary.beginFling(2500f, 1000f)
        assertEquals(0f, boundary.consume(20f, 100f, 0, false))
        assertEquals(30f, boundary.consume(50f, 20f, 10, false))
        assertEquals(100f, boundary.consume(100f, 0f, 1009, false))
        assertEquals(0f, boundary.consume(100f, 0f, 1010, false))
    }

    @Test fun `touch cancels pause immediately and a new slow fling stays free`() {
        val boundary = LatestResponseBoundary()
        boundary.beginFling(2500f, 1000f)
        boundary.consume(50f, 0f, 10, false)
        boundary.beginGesture()
        assertEquals(0f, boundary.consume(50f, 0f, 11, true))
        assertEquals(0f, boundary.consume(50f, 0f, 11, false))
        boundary.beginFling(200f, 1000f)
        assertEquals(0f, boundary.consume(50f, 0f, 12, false))
    }

    @Test fun `previous conversations and downward flings never stop at newest response`() {
        val boundary = LatestResponseBoundary()
        boundary.beginFling(2500f, 1000f)
        assertEquals(0f, boundary.consume(50f, -20f, 0, false))
        boundary.beginFling(-2500f, 1000f)
        assertEquals(0f, boundary.consume(-50f, 0f, 0, false))
        boundary.endFling()
        assertEquals(0f, boundary.consume(50f, 0f, 1, false))
    }
}
