package dev.chungjungsoo.gptmobile.presentation.ui.main

import androidx.lifecycle.SavedStateHandle

internal class NotificationNavigationQueue(private val savedState: SavedStateHandle) {
    val pending = savedState.getStateFlow("pendingNavigation", arrayListOf<String>())
    fun enqueue(route: String, identity: String = java.util.UUID.randomUUID().toString()) {
        val consumed = savedState.get<ArrayList<String>>("consumedNavigation").orEmpty()
        if (identity in consumed || pending.value.any { it.substringBefore('|') == identity }) return
        savedState["pendingNavigation"] = ArrayList(pending.value + "$identity|$route")
    }
    fun consume(event: String) {
        savedState["consumedNavigation"] = ArrayList((savedState.get<ArrayList<String>>("consumedNavigation").orEmpty() + event.substringBefore('|')).distinct().takeLast(100))
        savedState["pendingNavigation"] = ArrayList(pending.value.filterNot { it == event })
    }
}
