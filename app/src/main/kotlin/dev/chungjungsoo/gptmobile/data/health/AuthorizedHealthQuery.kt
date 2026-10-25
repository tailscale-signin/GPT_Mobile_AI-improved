package dev.chungjungsoo.gptmobile.data.health

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject

/** Exact query already parsed under a live grant; only the matching prepared call can use it. */
internal class AuthorizedHealthQuery(val arguments: JsonObject, val query: HealthQuery) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthorizedHealthQuery>
}
