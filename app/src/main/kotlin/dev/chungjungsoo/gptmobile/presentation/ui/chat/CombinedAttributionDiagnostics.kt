package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** A restored/recomposed answer emits a mapping only when that immutable mapping changes. */
internal object CombinedAttributionDiagnostics {
    private val emitted = LinkedHashMap<String, String>(64, 0.75f, true)

    @Synchronized fun changed(identity: String, digest: String): Boolean {
        if (emitted[identity] == digest) return false
        emitted[identity] = digest
        while (emitted.size > 512) emitted.remove(emitted.keys.first())
        return true
    }
}
