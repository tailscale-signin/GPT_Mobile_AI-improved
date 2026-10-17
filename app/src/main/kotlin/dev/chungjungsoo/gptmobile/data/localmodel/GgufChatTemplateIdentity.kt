package dev.chungjungsoo.gptmobile.data.localmodel

/** Human-readable signature for diagnostics; it does not claim a template is runtime-compatible. */
internal object GgufChatTemplateIdentity {
    fun describe(template: String?): String {
        val source = template?.takeIf(String::isNotBlank) ?: return "missing"
        val normalized = source.lowercase()
        val name = when {
            "<|start_header_id|>" in normalized -> "llama3"
            "<start_of_turn>" in normalized -> "gemma"
            "<|im_start|>" in normalized -> "chatml"
            "[inst]" in normalized && "[/inst]" in normalized -> "llama2"
            "<|user|>" in normalized && "<|assistant|>" in normalized -> "phi"
            "<|prompt|>" in normalized && "<|answer|>" in normalized -> "deepseek"
            else -> "custom"
        }
        return "$name:${templateFingerprint(source)}"
    }

    private fun templateFingerprint(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
