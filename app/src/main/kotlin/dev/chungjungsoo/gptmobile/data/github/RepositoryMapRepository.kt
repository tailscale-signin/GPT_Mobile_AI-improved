package dev.chungjungsoo.gptmobile.data.github

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The map's source is explicit and independent of the inference connection. */
@Singleton
class RepositoryMapRepository @Inject constructor(
    private val phone: PhoneRepositoryGraphRepository,
    private val gateway: GatewayGraphRepository
) {
    internal suspend fun executeForMap(
        context: GitHubConversationContext,
        arguments: JsonObject,
        expectedGatewayProfileUid: String? = null,
        refreshReadiness: Boolean = true,
        forModel: Boolean = false
    ): GatewayGraphResponse {
        val source = source(arguments)
        val payload = JsonObject(arguments - "map_source")
        val response = if (source == PHONE) {
            phone.executeForMap(context, payload, forModel)
        } else {
            gateway.executeForMap(context, payload, expectedGatewayProfileUid, refreshReadiness, forModel)
        }
        return response.copy(result = JsonObject(response.result + ("map_source" to JsonPrimitive(source))))
    }

    /** Keep Gateway's typed checkpoint observations intact when used by an AI tool. */
    internal suspend fun execute(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
        val source = source(arguments)
        val payload = JsonObject(arguments - "map_source")
        val result = if (source == PHONE) {
            phone.executeForMap(context, payload, forModel = true).result
        } else {
            gateway.execute(context, payload)
        }
        return JsonObject(result + ("map_source" to JsonPrimitive(source)))
    }

    companion object {
        const val PHONE = "phone"
        const val GATEWAY = "gateway"

        internal fun source(arguments: JsonObject): String {
            if ("map_source" !in arguments) return PHONE
            val value = (arguments["map_source"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (value !in setOf(PHONE, GATEWAY)) {
                throw GatewayGraphAdmissionException("INVALID_ARGUMENTS", "Choose phone or gateway as the repository map source.")
            }
            return requireNotNull(value)
        }
    }
}
