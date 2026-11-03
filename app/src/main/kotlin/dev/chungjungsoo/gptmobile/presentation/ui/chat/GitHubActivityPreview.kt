package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.PreviewKind
import java.util.Locale

@Composable
internal fun GitHubActivityPreview(raw: String, latest: Boolean, motion: State<Float>, search: GitHubSearchActivity? = null) {
    val text = search?.label ?: remember(raw) { GitHubProgressPresentation.preview(raw).replace(Regex("\\s+"), " ") }
    val kind = remember(text) { GitHubProgressPresentation.previewKind(text) }
    val colors = MaterialTheme.colorScheme
    val previewColor = colors.onSurface.copy(alpha = 0.5f)
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) { opacity.animateTo(1f, tween(1500)) }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("github_preview_row")
            // Apply fade and highlight once to the whole row so the icon and
            // text always share opacity, color and animation phase.
            .graphicsLayer {
                alpha = opacity.value
                compositingStrategy = CompositingStrategy.Offscreen
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
        Text(
            text,
            modifier = Modifier.weight(1f).testTag(if (latest) "github_latest_preview" else "github_previous_preview"),
            style = MaterialTheme.typography.bodyMedium,
            color = previewColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
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
