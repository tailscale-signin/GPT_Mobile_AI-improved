package dev.chungjungsoo.gptmobile.data.pairing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable

/** A pairing code grants one configuration fetch, never authority to run a model or tools. */
data class PairingLink(val endpoint: String, val code: String, val expiresAt: Long) {
    companion object {
        fun parse(link: String, now: Long = System.currentTimeMillis() / 1000): PairingLink {
            require(link.length <= 4096)
            val uri = URI(link)
            require(uri.scheme == "gptmobile" && uri.host == "pair")
            val values = uri.rawQuery.orEmpty().split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            val endpoint = values.getValue("endpoint")
            validatePairingEndpoint(endpoint)
            val code = values.getValue("code")
            require(Regex("[A-Za-z0-9_-]{43}").matches(code))
            val expiry = values.getValue("expires").toLong()
            require(expiry > now && expiry <= now + 600) { "Pairing code expired or lifetime is invalid." }
            return PairingLink(endpoint, code, expiry)
        }
    }
}

internal fun validatePairingEndpoint(url: String) {
    val endpoint = URI(url)
    require(endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
    require(endpoint.scheme == "https" || (endpoint.scheme == "http" && endpointLocality(url) != EndpointLocality.EXTERNAL))
}

@Serializable
data class PairedServer(val version: Int, val name: String, val provider: String, val apiUrl: String, val model: String, val expiresAt: Long) {
    fun profile(now: Long = System.currentTimeMillis() / 1000): PlatformV2 {
        require(version == 1 && expiresAt > now && expiresAt <= now + 600)
        require(name.isNotBlank() && name.length <= 100 && model.isNotBlank() && model.length <= 200)
        val type = ClientType.valueOf(provider)
        require(type in setOf(ClientType.LLAMA, ClientType.OLLAMA, ClientType.CUSTOM))
        validatePairingEndpoint(apiUrl)
        return PlatformV2(name = name, compatibleType = type, apiUrl = apiUrl, model = model, enabled = false)
    }
}
