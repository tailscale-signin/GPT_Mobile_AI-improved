package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun ConversationDelegationCard(
    settings: ModelDelegationSettings,
    profiles: List<PlatformV2>,
    usesDefaults: Boolean,
    onChange: (ConversationDelegationSettings?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Enable delegation", style = MaterialTheme.typography.titleSmall)
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        onChange(ConversationDelegationSettings(enabled, settings.targetProfileUid, settings.allowRemoteWorkers, 100 - settings.processingOwnership, settings.strategy, cloudPermission = settings.cloudPermission, selectionMode = settings.selectionMode))
                    },
                    modifier = Modifier.testTag("conversation_delegation_toggle")
                )
            }
            if (settings.enabled) {
                DelegateModelDropdown(
                    profiles = profiles,
                    selectedProfileUid = settings.targetProfileUid,
                    enabled = true,
                    automaticLabel = "Settings default",
                    onSelected = { profile ->
                        onChange(ConversationDelegationSettings(true, profile?.uid.orEmpty(), settings.remoteWorkersAllowed(), 100 - settings.processingOwnership, settings.strategy, cloudPermission = settings.cloudPermission, selectionMode = if (profile == null) dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.INHERIT else dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.FIXED))
                    }
                )
            }
            if (settings.enabled) {
                TextButton(onClick = { onChange(ConversationDelegationSettings(true, "", settings.remoteWorkersAllowed(), 100 - settings.processingOwnership, settings.strategy, cloudPermission = settings.cloudPermission, selectionMode = dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.AUTOMATIC)) }) { Text("Choose automatically from the approved pool") }
                var amount by remember(settings.processingOwnership) { mutableFloatStateOf((100 - settings.processingOwnership).toFloat()) }
                var depth by remember(settings.strategy) { mutableFloatStateOf(settings.strategy.toFloat()) }
                Text("Delegation amount · ${amount.toInt()}%", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(value = amount, valueRange = 0f..100f, onValueChange = { amount = it }, onValueChangeFinished = {
                    onChange(ConversationDelegationSettings(true, settings.targetProfileUid, settings.allowRemoteWorkers, amount.toInt(), depth.toInt(), cloudPermission = settings.cloudPermission, selectionMode = settings.selectionMode))
                })
                Text("Research depth · ${depth.toInt()}%", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(value = depth, valueRange = 0f..100f, onValueChange = { depth = it }, onValueChangeFinished = {
                    onChange(ConversationDelegationSettings(true, settings.targetProfileUid, settings.allowRemoteWorkers, amount.toInt(), depth.toInt(), cloudPermission = settings.cloudPermission, selectionMode = settings.selectionMode))
                })
            }
            Text(if (usesDefaults) "Inherit settings" else "Custom for this chat", style = MaterialTheme.typography.labelMedium)
            Text("Global and profile participation must both allow delegation. Changes apply to the next response.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow cloud helpers for this chat", Modifier.weight(1f))
                Switch(checked = settings.remoteWorkersAllowed(), onCheckedChange = { allowed ->
                    onChange(
                        ConversationDelegationSettings(
                            settings.enabled,
                            settings.targetProfileUid,
                            settings.remoteWorkersAllowed(),
                            100 - settings.processingOwnership,
                            settings.strategy,
                            selectionMode = settings.selectionMode,
                            cloudPermission = if (allowed) dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.INHERIT else dev.chungjungsoo.gptmobile.data.delegation.ScopedPermission.DENY
                        )
                    )
                })
            }
            if (!usesDefaults) {
                TextButton(onClick = { onChange(null) }) { Text("Use settings defaults") }
            }
        }
    }
}
