package dev.chungjungsoo.gptmobile.data.airbnb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirbnbGeographyTest {
    @Test
    fun rejectsForeignAndDistantListingsMarksUnknown() {
        val origin = AirbnbListing("1", "Toronto", "https://www.airbnb.com/rooms/1", latitude = 43.6532, longitude = -79.3832)
        val far = origin.copy(id = "2", latitude = 46.4917, longitude = -80.9930)
        val foreign = origin.copy(id = "3", latitude = 50.947, longitude = 2.122)
        val unknown = origin.copy(id = "4", latitude = null, longitude = null)
        val result = AirbnbGeography.filter(listOf(far, foreign, unknown, origin), 43.6532, -79.3832, 25.0)
        assertEquals(listOf("1", "4"), result.map { it.id })
        assertTrue(result.first().distanceVerified)
        assertFalse(result.last().distanceVerified)
    }
}
