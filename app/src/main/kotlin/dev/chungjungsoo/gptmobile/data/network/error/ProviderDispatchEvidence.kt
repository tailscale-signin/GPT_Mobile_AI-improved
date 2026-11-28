package dev.chungjungsoo.gptmobile.data.network.error

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** HTTP delivery never supplies missing evidence that upstream inference was dispatched. */
internal object ProviderDispatchEvidence {
    fun responseFields(details: JsonObject?, payloadObserved: Boolean, knownNotAccepted: Boolean, reconciliationRequired: Boolean): JsonObject {
        val upstreamState = (details?.get("dispatch_state") as? JsonPrimitive)?.contentOrNull?.uppercase(Locale.ROOT)
        fun flag(name: String) = (details?.get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val explicitUpstream = flag("upstream_inference_dispatched") ?: flag("inference_dispatched")
        val legacyDispatch = flag("dispatched").takeUnless { details?.get("dispatch_scope") == JsonPrimitive("HTTP_REQUEST") }
        val inferenceDispatched = when {
            payloadObserved || explicitUpstream == true -> true
            reconciliationRequired -> null
            explicitUpstream == false || upstreamState in setOf("NOT_DISPATCHED", "PRECHECK_REJECTED") -> false
            upstreamState in setOf("DISPATCHED", "COMPLETED") -> true
            legacyDispatch != null -> legacyDispatch
            knownNotAccepted -> false
            else -> null
        }
        return buildJsonObject {
            put("http_request_dispatched", true)
            inferenceDispatched?.let { put("upstream_inference_dispatched", it) }
            put("dispatch_scope", if (inferenceDispatched == null) "HTTP_REQUEST" else "UPSTREAM_INFERENCE")
            put("dispatched", inferenceDispatched ?: true)
            if (inferenceDispatched == false) put("dispatch_state", if (upstreamState == "PRECHECK_REJECTED") "PRECHECK_REJECTED" else "NOT_DISPATCHED")
            if (inferenceDispatched == true && upstreamState in setOf("NOT_DISPATCHED", "PRECHECK_REJECTED")) put("dispatch_state", "DISPATCHED")
        }
    }
}
