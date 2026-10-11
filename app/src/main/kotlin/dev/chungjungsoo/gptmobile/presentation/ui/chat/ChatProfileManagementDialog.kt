package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

/** Resuming is always safe; pausing must leave an unpaused participant. */
internal fun canPauseChatProfile(uid: String, members: Set<String>, paused: Set<String>): Boolean =
    uid in members && (uid in paused || (members - paused - uid).isNotEmpty())

@Composable
internal fun ChatProfileManagementDialog(
    profiles: List<PlatformV2>,
    pausedUids: Set<String>,
    onPause: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    nextMessageUid: String? = null,
    onNextMessage: ((String?) -> Unit)? = null
) {
    val members = profiles.mapTo(mutableSetOf()) { it.uid }
    FadingAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_chat_profiles)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.chat_profile_management_help)) }
                items(profiles, key = { it.uid }) { profile ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(profile.model, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(if (profile.uid in pausedUids) R.string.chat_profile_paused else R.string.chat_profile_active),
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(
                            onClick = { onPause(profile.uid) },
                            enabled = canPauseChatProfile(profile.uid, members, pausedUids),
                            modifier = Modifier.testTag("profile_pause_${profile.uid}")
                        ) {
                            Text(stringResource(if (profile.uid in pausedUids) R.string.chat_profile_resume else R.string.chat_profile_pause))
                        }
                        TextButton(
                            onClick = { onRemove(profile.uid) },
                            enabled = (members - pausedUids - profile.uid).isNotEmpty(),
                            modifier = Modifier.testTag("profile_remove_${profile.uid}")
                        ) { Text(stringResource(R.string.chat_profile_remove)) }
                        if (onNextMessage != null) {
                            TextButton(
                                onClick = { onNextMessage(if (nextMessageUid == profile.uid) null else profile.uid) },
                                enabled = profile.enabled && profile.uid !in pausedUids
                            ) { Text(stringResource(if (nextMessageUid == profile.uid) R.string.next_message_clear else R.string.next_message_use)) }
                        }
                        HorizontalDivider()
                    }
                }
                item { Text(stringResource(R.string.chat_last_profile_required), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
