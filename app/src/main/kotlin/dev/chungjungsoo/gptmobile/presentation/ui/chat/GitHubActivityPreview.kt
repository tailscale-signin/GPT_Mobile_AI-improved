package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.PreviewKind
import java.util.Locale

@Composable
internal fun GitHubActivityPreview(raw: String, latest: Boolean, motion: State<Float>, search: GitHubSearchActivity? = null, animateEntry: Boolean = true) {
    val text = search?.label ?: remember(raw) { GitHubProgressPresentation.preview(raw) }
    val kind = remember(text) { GitHubProgressPresentation.previewKind(text) }
    val colors = MaterialTheme.colorScheme
    val previewColor = colors.onSurface.copy(alpha = 0.5f)
    val opacity = rememberGitHubActivityEntry(animateEntry)
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("github_preview_row")
            .graphicsLayer {
                alpha = if (latest) 1f else opacity.value
                compositingStrategy = if (latest) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .then(
                if (latest) {
                    Modifier.drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(githubProgressBrush(size.width, motion.value, previewColor.copy(alpha = 1f), colors.primary), blendMode = BlendMode.SrcIn)
                        }
                    }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.graphicsLayer { alpha = if (latest) opacity.value else 1f }) {
            if (search != null) {
                GitHubSearchBrandStack(search)
            } else {
                Icon(
                    imageVector = kind.outlineIcon(),
                    contentDescription = null,
                    tint = previewColor,
                    modifier = Modifier.size(18.dp).testTag("github_preview_icon_${kind.name.lowercase(Locale.ROOT)}")
                )
            }
        }
        val counter = GitHubProgressPresentation.liveKey(text) != null
        // Counters update in their stable row, without replacing its accessibility node.
        GitHubActivityCrossfade(text, Modifier.weight(1f), animateChanges = !counter && !latest) { displayed, current ->
            Text(
                displayed,
                modifier = Modifier.fillMaxWidth().then(
                    if (current) {
                        Modifier.testTag(if (latest) "github_latest_preview" else "github_previous_preview")
                            // Text semantics retain the complete public description even
                            // when the visual preview is collapsed.
                            .semantics { if (latest || counter) liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (latest) colors.onSurface.copy(alpha = 1f) else previewColor,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = { if (current && !expanded) overflows = it.hasVisualOverflow }
            )
        }
        if (overflows) {
            val detailsState = stringResource(if (expanded) R.string.expanded_state else R.string.collapsed_state)
            val detailsAction = stringResource(if (expanded) R.string.collapse else R.string.expand)
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(48.dp).testTag("github_preview_details")
                    .graphicsLayer { alpha = if (latest) opacity.value else 1f }
                    .semantics { stateDescription = detailsState }
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = detailsAction,
                    tint = previewColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun PreviewKind.outlineIcon(): ImageVector = when (this) {
    PreviewKind.WEB -> Icons.Outlined.Language
    PreviewKind.INSPECT -> Icons.Outlined.Search
    PreviewKind.EDIT -> Icons.Outlined.Edit
    PreviewKind.CHECKS -> Icons.Outlined.Science
    PreviewKind.BRANCH -> Icons.Outlined.AccountTree
    PreviewKind.WAIT -> Icons.Outlined.Schedule
    PreviewKind.ATTENTION -> Icons.Outlined.ErrorOutline
    PreviewKind.REVIEW -> Icons.Outlined.FactCheck
    PreviewKind.ACTIVITY -> Icons.Outlined.Settings
}
