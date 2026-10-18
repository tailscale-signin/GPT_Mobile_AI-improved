package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ProfileLabel
import dev.chungjungsoo.gptmobile.data.model.collectReusableProfileLabels
import dev.chungjungsoo.gptmobile.data.model.parseProfileLabels
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey
import dev.chungjungsoo.gptmobile.presentation.common.ProfileLabelChip
import dev.chungjungsoo.gptmobile.presentation.common.ProfileSearchField
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.profileMatchesQuery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectPlatformDialog(
    platforms: List<PlatformV2>,
    selectedPlatforms: List<Boolean>,
    onDismissRequest: () -> Unit,
    onConfirmation: (enabledPlatforms: List<String>, conversationMode: String) -> Unit,
    onPlatformSelect: (uid: String) -> Unit,
    onTogglePlatformFavorite: (platformId: Int) -> Unit = {},
    initialCombined: Boolean = false,
    insights: Map<String, ProfileInsight> = emptyMap(),
    onRefreshPrices: () -> Unit = {}
) {
    val window = LocalWindowInfo.current
    val height = with(LocalDensity.current) { window.containerSize.height.toDp() }
    var combined by rememberSaveable { mutableStateOf(initialCombined) }
    var query by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsUid by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsContext by rememberSaveable { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val selected = selectedProfileUids(platforms, selectedPlatforms)
    val labels = remember(platforms) { collectReusableProfileLabels(platforms.map { it.labels }) }
    LaunchedEffect(selected.size) { if (selected.size < 2) combined = false }
    LaunchedEffect(labels) { if (label != null && labels.none { it.key == label }) label = null }
    val visible = remember(platforms, query, label) {
        orderedChatProfiles(platforms).map { it.second }.filter { profile ->
            profileMatchesQuery(profile, query) && (label == null || parseProfileLabels(profile.labels).any { it.key == label })
        }
    }
    val detail = detailsUid?.let { uid -> insights[uid]?.takeIf { insight -> platforms.any { it.uid == uid && insight.matches(it) && priceContextKey(it) == detailsContext } } }
    LaunchedEffect(detailsUid, detail) { if (detailsUid != null && detail == null) detailsUid = null }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.imePadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (height - 48.dp).coerceAtLeast(160.dp)).testTag("select_platform_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens().background,
            contentColor = dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens().foreground,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.select_platform), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.picker_help)) }
                }
                LazyColumn(Modifier.weight(1f, fill = false).testTag("picker_list"), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(selected = !combined, onClick = { combined = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("picker_mode_separate")) { Text(stringResource(R.string.picker_separate)) }
                                SegmentedButton(selected = combined, enabled = selected.size >= 2, onClick = { combined = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("picker_mode_combined")) { Text(stringResource(R.string.chat_mode_combined)) }
                            }
                            ProfileSearchField(query, { query = it }, Modifier.testTag("picker_search"))
                            if (labels.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProfileLabelChip(ProfileLabel(stringResource(R.string.all_profile_labels)), label == null, { label = null })
                                    labels.forEach { item -> ProfileLabelChip(item, item.key == label, { label = item.key }) }
                                }
                            }
                            if (insights.values.any { it.settings.newChatPrices }) Text(stringResource(R.string.picker_rate_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(visible, key = { it.uid }) { profile ->
                        SelectPlatformProfileCard(profile, profile.uid in selected, insights[profile.uid]?.takeIf { it.matches(profile) }, onPlatformSelect, { onTogglePlatformFavorite(profile.id) }) {
                            detailsUid = profile.uid
                            detailsContext = priceContextKey(profile)
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            when {
                                platforms.isEmpty() -> Text(stringResource(R.string.picker_no_profiles))
                                platforms.none { it.enabled } -> EnablePlatformWarningText()
                                else -> Column {
                                    Text(stringResource(R.string.picker_no_matches))
                                    TextButton(onClick = {
                                        query = ""
                                        label = null
                                    }) { Text(stringResource(R.string.picker_clear_filters)) }
                                }
                            }
                        }
                    }
                }
                val hidden = selected.count { uid -> visible.none { it.uid == uid } }
                Text(pluralStringResource(R.plurals.picker_selected_count, selected.size, selected.size), style = MaterialTheme.typography.labelMedium)
                if (hidden > 0) Text(pluralStringResource(R.plurals.picker_hidden_count, hidden, hidden), style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
                    Button(enabled = selected.isNotEmpty() && !confirming, modifier = Modifier.testTag("picker_confirm"), onClick = {
                        val valid = confirmedProfileUids(platforms, selected)
                        if (!confirming && valid.isNotEmpty()) {
                            confirming = true
                            onConfirmation(valid, if (combined && valid.size >= 2) ConversationMode.COMBINED else ConversationMode.STANDARD)
                        }
                    }) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.picker_help)) }, text = { Text(stringResource(R.string.picker_mode_help)) }, confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) } })
    detail?.let { ProfileInsightDetailsDialog(it, onRefreshPrices, { onTogglePlatformFavorite(it.profile.id) }, { detailsUid = null }) }
}
