package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacySearchArgumentsTest {
    private fun json(value: String) = Json.decodeFromString<JsonObject>(value)

    @Test
    fun translatesProviderAliasesWithoutForwardingUnrelatedParameters() {
        assertEquals(
            json("""{"query":"news","maxResults":5,"includeDomains":["cbc.ca","ctvnews.ca"],"recencyDays":7}"""),
            legacySearchArguments(json("""{"query":"news","num_results":"5","include_domains":"cbc.ca, ctvnews.ca","recency_days":"7","engine":"google","api_key":"unused"}"""))
        )
    }

    @Test
    fun invalidValuesRemainInvalidAndCanonicalFiltersTakePrecedence() {
        assertEquals(
            json("""{"query":"news","maxResults":-1,"includeDomains":[12]}"""),
            legacySearchArguments(json("""{"query":"news","maxResults":-1,"count":5,"includeDomains":[12],"include_domains":["example.org"]}"""))
        )
    }

    @Test
    fun boundsLegacyCountAndKeepsExplicitTotal() {
        assertEquals(json("""{"query":"news","maxResults":10,"totalResults":50}"""), legacySearchArguments(json("""{"query":"news","maxResults":"500"}""")))
        assertEquals(json("""{"query":"news","maxResults":10,"totalResults":15}"""), legacySearchArguments(json("""{"query":"news","maxResults":20,"totalResults":15}""")))
    }
}
