package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** One visibility owner for title, navigation, actions, progress and header decoration. */
@Stable
internal class GitHubHeaderState(initialExpanded: Boolean = false, private var userSelected: Boolean = false) {
    var expanded by mutableStateOf(initialExpanded)
        private set

    fun isExpanded(collapsible: Boolean): Boolean = !collapsible || expanded

    fun toggle() {
        userSelected = true
        expanded = !expanded
    }

    fun restorePreference(value: Boolean) {
        // A delayed disk read cannot undo a tap, including one restored after rotation.
        if (!userSelected) expanded = value
    }

    companion object {
        val Saver = Saver<GitHubHeaderState, Boolean>(
            save = { it.expanded },
            restore = { GitHubHeaderState(it, userSelected = true) }
        )
    }
}
