package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConversationPaneViewModel @Inject constructor(database: ChatDatabaseV2) : ViewModel() {
    val chats = database.chatRoomDao().observeNavigationChats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun AdaptiveConversationLayout(selectedId: Int, onSelect: (Int) -> Unit, content: @Composable () -> Unit) {
    val model: ConversationPaneViewModel = hiltViewModel()
    val chats by model.chats.collectAsState()
    val activity = LocalContext.current.findActivity()
    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(activity) { activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it).collect { info -> fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { feature -> feature.isSeparating || feature.occlusionType == FoldingFeature.OcclusionType.FULL } } } }
    val density = LocalDensity.current
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    val latestContent by rememberUpdatedState(content)
    val stableDetail = remember { movableContentOf { latestContent() } }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            originX = it.positionInWindow().x
            originY = it.positionInWindow().y
        }
    ) {
        val vertical = fold?.takeIf { it.orientation == FoldingFeature.Orientation.VERTICAL }
        val left = vertical?.let { with(density) { (it.bounds.left - originX).toDp() } }
        val gap = vertical?.let { with(density) { it.bounds.width().toDp() } } ?: 0.dp
        val horizontalInset = fold?.takeIf { it.orientation == FoldingFeature.Orientation.HORIZONTAL }?.let { with(density) { (it.bounds.bottom - originY).toDp() }.coerceIn(0.dp, maxHeight) } ?: 0.dp
        val split = if (left != null) left >= 280.dp && maxWidth - left - gap >= 360.dp else maxWidth >= 840.dp
        val singleInset = if (!split && vertical != null) with(density) { (vertical.bounds.right - originX).toDp() }.coerceIn(0.dp, maxWidth) else 0.dp
        Row(Modifier.fillMaxSize().padding(top = horizontalInset, start = singleInset)) {
            if (split) {
                Surface(Modifier.width(left ?: 300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp)) }
                        items(chats, key = { it.id }) { chat ->
                            FilledTonalButton(onClick = { if (chat.id != selectedId) onSelect(chat.id) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (chat.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(chat.title, maxLines = 2)
                                    if (!chat.draftText.isNullOrBlank()) Text("Draft · ${chat.draftText.take(60)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(if (gap > 0.dp) gap else 1.dp))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { stableDetail() }
        }
    }
}
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
