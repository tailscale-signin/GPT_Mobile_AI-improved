package dev.chungjungsoo.gptmobile.data.voice

import java.security.MessageDigest

/** Private calibration identity. Addresses/names never enter diagnostics or preference key text. */
internal object VoiceCaptionRouteIdentity {
    fun key(category: String, type: Int, address: String, product: String, sampleRate: Int): String {
        require(category.matches(Regex("[a-z0-9_]{1,32}")) && type >= 0 && sampleRate > 0)
        // AudioDeviceInfo.id is session-local. Prefer a stable device address; use the product
        // descriptor when Android withholds it. Missing identity remains a type-level fallback.
        val identity = address.trim().takeIf { it.isNotEmpty() }?.let { "address:$it" }
            ?: product.trim().takeIf { it.isNotEmpty() }?.let { "product:$it" }
            ?: "unidentified"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$type\n$sampleRate\nfloat-mono\n$identity".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "caption:v2:$category:$digest"
    }

    fun observedRoute(key: String, address: String, product: String): String? {
        if (address.isBlank() && product.isBlank()) return null
        val value = key.removePrefix("caption:v2:")
        val category = value.substringBefore(':').takeIf { it in setOf("speaker", "earpiece", "bluetooth", "usb", "wired") } ?: "other"
        return "$category:${value.substringAfterLast(':')}"
    }
}
