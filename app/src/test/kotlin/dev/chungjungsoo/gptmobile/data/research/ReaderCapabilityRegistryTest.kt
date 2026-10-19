package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.CombinedToolWork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCapabilityRegistryTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""").jsonObject

    @Test fun reviewedLegacyOperationsShareOneSchemaContractAcrossWorkflowsAndBudgeting() {
        for (name in listOf("read_url", "firecrawl_scrape", "tavily_extract", "web_fetch_exa", "crawling_exa", "scrape_as_markdown", "free_search__fetch", "free_search__read_doc")) {
            val definition = AgentToolDefinition(name, "read", schema)
            assertTrue(ReaderCapabilityRegistry.readsUrl(definition))
            assertTrue(CombinedToolWork.isInspection(definition))
        }
        val namespaced = AgentToolDefinition("mcp__approved__read_url", "read", schema, setOf(AgentToolCapability.PAGE_READER))
        assertTrue(CombinedToolWork.isInspection(namespaced))
    }

    @Test fun crawlDisplayNamesAndUnwiredCrawlerContractsDoNotGrantReaderAdmission() {
        for (name in listOf("crawl_everything", "crawler_fetch", "crawler_crawler_fetch", "fetch", "malicious_read_url")) {
            assertFalse(ReaderCapabilityRegistry.legacyUrlReader(name, schema))
        }
    }

    @Test fun changedSchemaOrAdditionalRequiredAuthorityIsRejected() {
        for (json in listOf(
            """{"properties":{"url":{"type":"integer"}},"required":["url"]}""",
            """{"properties":{"urls":{"type":"array","items":{"type":"object"}}},"required":["urls"]}""",
            """{"properties":{"url":{"type":"string"},"grant":{"type":"string"}},"required":["url","grant"]}"""
        )) {
            assertFalse(ReaderCapabilityRegistry.legacyUrlReader("read_url", Json.parseToJsonElement(json).jsonObject))
        }
    }
}
