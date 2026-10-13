package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonObject

/** In-process progress only. Remote tool arguments cannot install a listener. */
internal class AcademicDiscoveryObserver(val accept: suspend (List<JsonObject>) -> Unit) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AcademicDiscoveryObserver>
}
