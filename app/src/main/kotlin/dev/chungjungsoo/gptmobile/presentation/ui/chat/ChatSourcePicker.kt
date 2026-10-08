package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatResponseSources(answer: String, events: List<ToolEvent>, contentIdentity: Any, isLoading: Boolean, modifier: Modifier = Modifier) {
    key(contentIdentity) {
        val finishedAnswer = if (isLoading) "" else answer
        val sources by produceState(ChatSources(emptyList(), emptyList()), events, finishedAnswer) {
            value = withContext(Dispatchers.Default) { collectChatSources(finishedAnswer, events) }
        }
        ChatSourcePicker(sources, contentIdentity, modifier)
    }
}

/** Compact transparent provenance action beside the response's other actions. */
@Composable
internal fun ChatSourcePicker(sources: ChatSources, contentIdentity: Any, modifier: Modifier = Modifier) {
    if (sources.isEmpty) return
    var origin by remember(contentIdentity) { mutableStateOf<Offset?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val label = pluralStringResource(R.plurals.chat_source_count, sources.sources.size, sources.sources.size)
    Row(
        modifier = modifier
            .testTag("response-sources")
            .onGloballyPositioned { center = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f)) }
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button) { origin = center }
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(sources.sources.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Rounded.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
    origin?.let { anchor -> SourceListDialog(sources, anchor) { origin = null } }
}

@Composable
private fun SourceLogo(brand: ChatSourceBrand?, engine: Boolean, modifier: Modifier = Modifier) {
    val background = when {
        brand == null -> MaterialTheme.colorScheme.primaryContainer
        brand.monochrome -> Color(brand.color)
        else -> Color.White
    }
    Surface(modifier, shape = CircleShape, color = background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) {
            if (brand != null) {
                Image(
                    painterResource(brand.icon),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(7.dp),
                    colorFilter = if (brand.monochrome) ColorFilter.tint(if (background.luminance() > 0.5f) Color.Black else Color.White) else null
                )
            } else {
                Icon(
                    if (engine) Icons.Rounded.Search else Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxSize().padding(7.dp)
                )
            }
        }
    }
}

@Composable
private fun SourceListDialog(sources: ChatSources, origin: Offset, onDismiss: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.chat_sources_title)
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(sources) { sources.sources.mapNotNull { chatSourceBrand(it.host) }.distinctBy { it.id }.sortedBy { it.name } }
    val visibleSources = remember(sources, group) { filterChatSources(sources.sources, group) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                onDismiss()
            }
        }
    }
    LaunchedEffect(center != null) {
        if (center != null && !closing) progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss)
                .safeDrawingPadding()
                .imePadding()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            val dialogMaxHeight = maxHeight * 0.85f
            // Measure an untransformed parent in screen coordinates. Both windows then share
            // the same origin even with status bars, split screen, or the keyboard visible.
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { center = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f)) }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogMaxHeight)
                        .testTag("response-source-dialog")
                        .semantics { paneTitle = title }
                        .graphicsLayer {
                            val amount = progress.value
                            scaleX = amount
                            scaleY = amount
                            alpha = amount
                            transformOrigin = TransformOrigin.Center
                            center?.let { target ->
                                translationX = (origin.x - target.x) * (1f - amount)
                                translationY = (origin.y - target.y) * (1f - amount)
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_sources_close)) }
                        }
                        Text(pluralStringResource(R.plurals.chat_source_count, sources.sources.size, sources.sources.size), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All") }) }
                            items(groups, key = { it.id }) { brand ->
                                FilterChip(selected = group == brand.id, onClick = { group = brand.id }, leadingIcon = { SourceLogo(brand, false, Modifier.size(22.dp)) }, label = { Text(brand.name) })
                            }
                            if (sources.sources.any { sourceGroupId(it) == "other" }) {
                                item { FilterChip(selected = group == "other", onClick = { group = "other" }, label = { Text("Other") }) }
                            }
                        }
                        if (sources.engines.isNotEmpty()) {
                            Text(stringResource(R.string.chat_sources_engines), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium)
                            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(sources.engines, key = { it }) { engine ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SourceLogo(chatSearchEngineBrand(engine), true, Modifier.size(28.dp))
                                        Text(engine, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (sources.sources.isEmpty()) {
                            Text(stringResource(R.string.chat_sources_none), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(Modifier.weight(1f, fill = false).testTag("response-source-list")) {
                                items(visibleSources, key = { it.url }) { source -> SourceListRow(source) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceListRow(source: ChatSource) {
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    val error = stringResource(R.string.chat_sources_link_error)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                runCatching { handler.openUri(source.url) }.onFailure { Toast.makeText(context, error, Toast.LENGTH_SHORT).show() }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SourceLogo(chatSourceBrand(source.host), false, Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(source.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(source.compactLink, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
