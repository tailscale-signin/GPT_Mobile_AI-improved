package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmazonProductInformationTest {
    @Test fun descriptionAndHighlightsDoNotRepeatTheTitleOrEachOther() {
        val product = Json.parseToJsonElement("""{"title":"Headphones","description":"Comfortable headphones","features":["Headphones","Comfortable headphones","Noise cancelling"," noise   cancelling ","• Long battery life",""]}""") as JsonObject
        assertEquals("Comfortable headphones", amazonProductDescription(product))
        assertEquals(listOf("Noise cancelling", "Long battery life"), amazonProductHighlights(product))
        assertNull(amazonProductDescription(Json.parseToJsonElement("""{"title":"Headphones","description":" Headphones "}""") as JsonObject))
    }

    @Test fun structuredFactsDeduplicateSpecificationsAndOmitEmptyOrInternalFields() {
        val product = Json.parseToJsonElement("""{"brand":"Acme","color":"Blue","specifications":{"Brand":"Acme","Color":"Blue","Material":"Steel","ASIN":"B000000001","empty":"","unknown":null}}""") as JsonObject
        assertEquals(listOf("Brand" to "Acme", "Colour" to "Blue", "Material" to "Steel"), amazonProductFacts(product))
    }

    @Test fun longProviderDescriptionsKeepUsefulInformationBeyondFiveHundredCharacters() {
        val description = "A".repeat(1200)
        val product = Json.parseToJsonElement("""{"title":"Headphones","description":"$description"}""") as JsonObject
        assertEquals(description, amazonProductDescription(product))
    }
}
