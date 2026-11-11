package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.AppGlassSurface
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

/** The same explicit action and permission scope, placed where the conversation pauses. */
@Composable
internal fun ChatInteractionSurface(
    inline: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null
) {
    if (!inline) {
        FadingAlertDialog(onDismissRequest = onDismissRequest, title = title, text = text, confirmButton = confirmButton, dismissButton = dismissButton, icon = icon)
        return
    }
    AppGlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("chat_inline_interaction"), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.invoke()
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() }
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) { text() }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                confirmButton()
                dismissButton()
            }
        }
    }
}
