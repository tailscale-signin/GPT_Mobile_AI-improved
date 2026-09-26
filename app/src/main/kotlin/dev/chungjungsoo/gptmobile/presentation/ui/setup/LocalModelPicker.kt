package dev.chungjungsoo.gptmobile.presentation.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalModelListItem

@Composable
fun LocalModelPicker(
    models: List<DownloadedLocalModelOption>,
    selectedCatalogEntryId: String,
    onModelSelected: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.local_platform_select_model),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.local_platform_select_model_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (models.isEmpty()) {
            Text(
                text = stringResource(R.string.local_platform_no_downloaded_models),
                style = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = onNavigateToLocalModels) {
                Text("Open model marketplace")
            }
        } else {
            models.forEach { option ->
                RadioItem(
                    value = option.catalogEntryId,
                    selected = option.catalogEntryId == selectedCatalogEntryId,
                    title = option.displayName,
                    description = option.catalogEntryId,
                    onSelected = onModelSelected,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun LocalModelCatalogPicker(
    items: List<LocalModelListItem>,
    selectedCatalogEntryId: String,
    checkingAccessEntryId: String?,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    showPendingActivationHint: Boolean = false,
    onNavigateToLocalModels: (() -> Unit)? = null
) {
    LocalModelPicker(
        models = items.filter { it.status == dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalModelItemStatus.READY }
            .map { DownloadedLocalModelOption(it.entry.id, it.entry.displayName) },
        selectedCatalogEntryId = selectedCatalogEntryId,
        onModelSelected = onModelSelected,
        onNavigateToLocalModels = { onNavigateToLocalModels?.invoke() },
        modifier = modifier
    )
}
