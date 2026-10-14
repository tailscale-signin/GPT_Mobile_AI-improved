package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CompletableDeferred

/** Called after the callback's final native access. Consumers may clean up as soon as a terminal event is visible. */
internal inline fun acknowledgeNativeTerminal(completion: CompletableDeferred<Unit>, publish: () -> Unit) {
    completion.complete(Unit)
    publish()
}
