package dev.chungjungsoo.gptmobile.data.privacy

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only an already masked provider session may select the dedicated inference client. */
internal object MaskedProviderCall : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<MaskedProviderCall>
}
