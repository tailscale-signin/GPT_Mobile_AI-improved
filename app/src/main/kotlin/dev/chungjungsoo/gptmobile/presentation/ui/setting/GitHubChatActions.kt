package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubChatActions(
    repositoryLoaded: Boolean,
    mapAvailable: Boolean,
    hasChanges: Boolean,
    hasTask: Boolean,
    onBrowseFiles: () -> Unit,
    onOpenChecks: () -> Unit,
    onOpenMap: () -> Unit,
    onReviewChanges: () -> Unit,
    taskDetails: @Composable (() -> Unit) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    if (detailsOpen && hasTask) {
        FadingModalBottomSheet(onDismissRequest = { detailsOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                taskDetails { detailsOpen = false }
            }
        }
    }
    if (!repositoryLoaded && !mapAvailable && !hasChanges && !hasTask) return
    Box {
        IconButton(onClick = { menuOpen = true }) {
            ThemeIcon(Icons.Rounded.MoreVert, "GitHub chat actions")
        }
        FadingDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val actions = buildList<Pair<String, () -> Unit>> {
                if (hasChanges) add("Review changes" to onReviewChanges)
                if (hasTask) add("Task details" to { detailsOpen = true })
                if (mapAvailable) add("Repository map" to onOpenMap)
                if (repositoryLoaded) {
                    add("Repository files" to onBrowseFiles)
                    add("Checks & builds" to onOpenChecks)
                }
            }
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    menuOpen = false
                    action()
                })
            }
        }
    }
}
