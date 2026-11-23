package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Retain a row's map identity when another turn changes, so Compose can skip unchanged rows. */
internal class ChatRunProjection<T> {
    private var retained: Map<String, T> = emptyMap()

    fun select(runIds: Set<String>, source: Map<String, T>): Map<String, T> {
        var selectedCount = 0
        var changed = false
        for (id in runIds) {
            if (!source.containsKey(id)) continue
            selectedCount++
            if (!retained.containsKey(id) || retained[id] != source[id]) changed = true
        }
        if (!changed && selectedCount == retained.size) return retained
        retained = buildMap {
            for (id in runIds) {
                if (source.containsKey(id)) put(id, source.getValue(id))
            }
        }
        return retained
    }
}
