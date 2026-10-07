package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PortablePluginConfiguration(val schemaVersion: Int = 1, val plugins: Map<String, PluginExecutionSettings> = emptyMap(), val connections: List<PortableConnection> = emptyList())

@Serializable
data class PortableConnection(val name: String, val type: String, val alias: String, val origin: String = "", val requiresCredential: Boolean = false)

object PluginConfiguration {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }
    fun validate(config: PluginExecutionSettings) {
        require(config == config.normalized()) { "Plugin option is out of range." }
        if (config.timeZone.isNotBlank()) java.time.ZoneId.of(config.timeZone)
        require(dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts.marketplace(config.amazonMarketplace) != null) { "Choose a supported Amazon marketplace." }
    }
    fun export(settings: Map<String, PluginExecutionSettings>, connections: List<ToolConnection>): String = json.encodeToString(
        PortablePluginConfiguration(
            plugins = settings,
            connections = connections.map {
                val uri = runCatching { URI(it.endpointUrl.orEmpty()) }.getOrNull()
                PortableConnection(it.name, it.type, it.alias, if (uri?.host != null && uri.scheme == "https") "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}" else "", it.authType != "NONE")
            }
        )
    )
    fun decode(text: String): PortablePluginConfiguration {
        require(text.length <= 128000) { "Configuration is too large." }
        val version = json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
        require(version in 0..1) { "Install a client supporting configuration version $version." }
        val config = json.decodeFromString<PortablePluginConfiguration>(text).copy(schemaVersion = 1)
        require(config.plugins.size <= 100 && config.connections.size <= 100)
        config.plugins.forEach { (id, value) ->
            require(id.matches(Regex("[a-zA-Z0-9_.:-]{1,120}")))
            validate(value)
        }
        config.connections.forEach {
            require(it.name.length in 1..120 && it.alias.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
            require(it.type in setOf(ToolConnectionType.MCP, ToolConnectionType.GITHUB, ToolConnectionType.AMAZON_SERPAPI, ToolConnectionType.FIRECRAWL, ToolConnectionType.PERPLEXITY, ToolConnectionType.EXA, ToolConnectionType.BRAVE))
            require(it.origin.isEmpty() || URI(it.origin).let { uri -> uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty() })
        }
        return config
    }
}
