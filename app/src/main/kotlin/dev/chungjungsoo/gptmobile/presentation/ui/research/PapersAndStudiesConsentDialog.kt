package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

@Composable
internal fun PapersAndStudiesConsentDialog(profileName: String? = null, onAccept: () -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit = onDecline) {
    FadingAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Papers and studies consent for this profile") },
        text = {
            Text("Allow ${profileName?.takeIf { it.isNotBlank() } ?: "this profile"} to retrieve public papers and study text from your selected research sources and send the material to its configured AI service for examination. Existing chat, provider and tool permissions still apply. Accept saves consent for this profile; you can revoke it in Article evidence.")
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } }
    )
}
