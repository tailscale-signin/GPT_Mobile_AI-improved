package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.chungjungsoo.gptmobile.R

@Composable
internal fun LocalModelImportChoice(copyModel: Boolean, linkUnavailable: Boolean, folder: Boolean, onCopyChanged: (Boolean) -> Unit) {
    if (linkUnavailable) {
        Text(stringResource(R.string.local_ai_link_unavailable))
    } else {
        Row(
            Modifier.fillMaxWidth().testTag("local_model_copy_choice").toggleable(copyModel, role = Role.Checkbox, onValueChange = onCopyChanged),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = copyModel, onCheckedChange = null)
            Text(stringResource(R.string.local_ai_copy_option))
        }
    }
    Text(stringResource(if (copyModel) R.string.local_ai_copy_review else R.string.local_ai_file_review))
    if (copyModel && folder) Text(stringResource(R.string.local_ai_copy_folder_review))
}
