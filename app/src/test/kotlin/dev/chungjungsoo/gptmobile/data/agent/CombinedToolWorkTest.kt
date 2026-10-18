package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedToolWorkTest {
    @Test
    fun batchedQueriesAndNestedCallsChargeTheirUnderlyingWorkUnits() {
        assertEquals(3L, CombinedToolWork.units(Json.parseToJsonElement("""{"queries":["a","b","c"]}""") as JsonObject))
        assertEquals(4L, CombinedToolWork.units(Json.parseToJsonElement("""{"calls":[{"arguments":{"urls":["a","b","c"]}},{"arguments":{"url":"d"}}]}""") as JsonObject))
        assertEquals(1L, CombinedToolWork.units(JsonObject(emptyMap())))
    }
}
