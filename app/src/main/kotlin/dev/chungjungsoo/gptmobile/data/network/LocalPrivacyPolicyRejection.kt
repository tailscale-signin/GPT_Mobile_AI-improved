package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import java.util.Collections
import java.util.IdentityHashMap

/** App-owned denials happen before the affected request sends HTTP bytes. Never match message text. */
internal fun Throwable.isLocalPrivacyPolicyRejection(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }.takeWhile(seen::add).take(16).any {
        it is UnverifiedServiceTransport || it is PrivateMetadataDenied
    }
}
