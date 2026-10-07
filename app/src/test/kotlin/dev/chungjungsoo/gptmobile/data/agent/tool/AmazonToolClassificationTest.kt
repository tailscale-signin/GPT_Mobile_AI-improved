package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonToolClassificationTest {
    @Test
    fun `retail requests and ASIN lookups use shopping tools`() {
        for (task in listOf("Find headphones on Amazon.ca under $100", "Compare Amazon product prices", "Search amazon.co.uk for a desk", "Get details for B000000001")) {
            assertTrue(task, isAmazonShoppingTask(task))
        }
    }

    @Test
    fun `AWS and geographic research do not become shopping requests`() {
        for (task in listOf("Search Amazon Bedrock documentation", "Compare AWS prices", "Find Amazon S3 API docs", "Search the Amazon river", "Find Amazon rainforest maps", "Explain Kotlin coroutines")) {
            assertFalse(task, isAmazonShoppingTask(task))
        }
    }
}
