package dev.chungjungsoo.gptmobile.data.agent.tool

import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class McpInputRequest(val id: String, val server: String, val message: String, val schema: JsonObject, val response: CompletableDeferred<ElicitResult>)

/** No URL elicitation, secrets, automatic answers or persisted form values. */
@Singleton
class McpInteractions @Inject constructor() {
    val pending = MutableStateFlow<List<McpInputRequest>>(emptyList())
    fun request(server: String, request: ElicitRequest): ElicitResult {
        val schema = runCatching { Json.encodeToJsonElement(request.requestedSchema).jsonObject }.getOrNull()
            ?: return ElicitResult(ElicitResult.Action.Decline)
        val properties = schema["properties"] as? JsonObject ?: return ElicitResult(ElicitResult.Action.Decline)
        if (properties.values.any { it !is JsonObject || !supportedFormField(it) }) return ElicitResult(ElicitResult.Action.Decline)
        if (properties.size > 16 || properties.keys.any { Regex("password|secret|token|credential|credit.card", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return ElicitResult(ElicitResult.Action.Decline)
        val entry = McpInputRequest(UUID.randomUUID().toString(), server, request.message.take(2000), schema, CompletableDeferred())
        return runBlocking(Dispatchers.IO) {
            pending.update { it + entry }
            try {
                withTimeoutOrNull(40_000) { entry.response.await() } ?: ElicitResult(ElicitResult.Action.Cancel)
            } finally {
                pending.update { items -> items.filterNot { it.id == entry.id } }
            }
        }
    }
    fun respond(id: String, values: JsonObject?) {
        val entry = pending.value.firstOrNull { it.id == id } ?: return
        if (values == null) {
            entry.response.complete(ElicitResult(ElicitResult.Action.Decline))
        } else if (values.toString().length <= 8000 && schemaError(entry.schema, values) == null) {
            entry.response.complete(ElicitResult(ElicitResult.Action.Accept, content = values))
        }
    }
}

internal fun supportedFormField(field: JsonObject): Boolean =
    (field["type"] as? JsonPrimitive)?.content in setOf("string", "number", "integer", "boolean") &&
        field.keys.all { it in setOf("type", "title", "description", "default", "enum", "enumNames", "minLength", "maxLength", "minimum", "maximum") }

/** Validates bounded object/array/primitive output contracts; never dereferences remote schemas. */
internal fun schemaError(schema: JsonObject, value: JsonElement, depth: Int = 0): String? {
    if (depth > 16) return "Schema nesting exceeds the supported limit."
    if (schema["$" + "ref"] != null) return "Referenced output schemas are not supported."
    if (schema.keys.any { it in setOf("pattern", "patternProperties", "format", "dependentSchemas", "contains", "unevaluatedProperties") }) return "This output schema uses an unsupported constraint."
    for (keyword in listOf("allOf", "anyOf", "oneOf")) {
        val alternatives = schema[keyword] as? JsonArray ?: continue
        if (alternatives.size > 32) return "Too many schema alternatives."
        val matches = alternatives.count { it is JsonObject && schemaError(it, value, depth + 1) == null }
        if ((keyword == "allOf" && matches != alternatives.size) || (keyword == "anyOf" && matches == 0) || (keyword == "oneOf" && matches != 1)) return "Output does not match the declared alternatives."
    }
    (schema["not"] as? JsonObject)?.let { if (schemaError(it, value, depth + 1) == null) return "Output matches a forbidden schema." }
    schema["const"]?.let { if (value != it) return "Output does not match the declared constant." }
    val types = schema["type"] as? JsonArray
    if (types != null && types.none { schemaError(JsonObject(schema + ("type" to it)), value, depth + 1) == null }) return "Output type does not match the declared schema."
    val expected = (schema["type"] as? JsonPrimitive)?.content
    val matches = when (expected) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value == JsonNull
        else -> true
    }
    if (!matches) return "Output type does not match the declared schema."
    (schema["enum"] as? JsonArray)?.let { if (value !in it) return "Value is outside the declared choices." }
    if (value is JsonPrimitive) {
        if (value.isString) {
            val length = value.content.codePointCount(0, value.content.length)
            if (length < ((schema["minLength"] as? JsonPrimitive)?.intOrNull ?: 0) || length > ((schema["maxLength"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE)) return "Text length is outside the declared range."
        } else {
            value.doubleOrNull?.let { number ->
                if (!number.isFinite() || number < ((schema["minimum"] as? JsonPrimitive)?.doubleOrNull ?: Double.NEGATIVE_INFINITY) || number > ((schema["maximum"] as? JsonPrimitive)?.doubleOrNull ?: Double.POSITIVE_INFINITY)) return "Number is outside the declared range."
            }
        }
    }
    if (value is JsonObject) {
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (required.any { it !in value }) return "Required output fields are missing."
        val properties = schema["properties"] as? JsonObject
        value.forEach { (key, child) ->
            val nested = properties?.get(key) as? JsonObject
            if (nested != null) {
                schemaError(nested, child, depth + 1)?.let { return it }
            } else {
                when (val extra = schema["additionalProperties"]) {
                    is JsonObject -> schemaError(extra, child, depth + 1)?.let { return it }
                    JsonPrimitive(false) -> return "Unexpected output fields."
                    else -> Unit
                }
            }
        }
    }
    if (value is JsonArray) {
        val itemSchema = schema["items"] as? JsonObject
        if (value.size < ((schema["minItems"] as? JsonPrimitive)?.intOrNull ?: 0) || value.size > ((schema["maxItems"] as? JsonPrimitive)?.intOrNull ?: 10000)) return "Array length is outside the declared range."
        if (schema["uniqueItems"] == JsonPrimitive(true) && value.distinct().size != value.size) return "Array items must be unique."
        if (value.size > 10000) return "Output array is too large."
        if (itemSchema != null) value.forEach { schemaError(itemSchema, it, depth + 1)?.let { error -> return error } }
    }
    return null
}
