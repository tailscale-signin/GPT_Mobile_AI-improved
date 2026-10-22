package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Anonymous text jobs must target a known instruction template, not arbitrary completion workers. */
internal object HordeTextPolicy {
    fun format(model: String): String? {
        val name = model.lowercase()
        if (listOf("thinking", "reasoning", "deepseek", "-r1").any { it in name }) return null
        return when {
            "instruct" in name && Regex("qwen2(?:\\.5)?(?:-|/)").containsMatchIn(name) -> "CHATML"
            "instruct" in name && Regex("llama-?3(?:\\.[123])?(?:-|/)").containsMatchIn(name) -> "LLAMA3"
            else -> null
        }
    }

    fun select(catalog: JsonArray, requested: String): String? = catalog.filterIsInstance<JsonObject>()
        .filter { ((it["count"] as? JsonPrimitive)?.intOrNull ?: 0) > 0 }
        .mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }
        .filter { format(it) != null && (requested == "automatic" || it == requested) }
        .sorted().firstOrNull()

    fun prompt(request: ChatCompletionRequest): String {
        val format = requireNotNull(format(request.model)) { "AI Horde model has no reviewed instruction template. Choose an available Qwen2/2.5 Instruct or Llama3 Instruct worker." }
        return request.messages.joinToString("") { message ->
            require(message.toolCalls.isNullOrEmpty() && message.content.orEmpty().all { it is TextContent }) { "AI Horde text workers do not support attachments or tools." }
            val role = message.role.name.lowercase()
            require(role in setOf("system", "user", "assistant")) { "Unsupported AI Horde chat role" }
            val content = message.content.orEmpty().filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (format == "CHATML") "<|im_start|>$role\n$content<|im_end|>\n" else "<|start_header_id|>$role<|end_header_id|>\n\n$content<|eot_id|>"
        }.let { if (format == "CHATML") it + "<|im_start|>assistant\n" else "<|begin_of_text|>" + it + "<|start_header_id|>assistant<|end_header_id|>\n\n" }
    }
}
