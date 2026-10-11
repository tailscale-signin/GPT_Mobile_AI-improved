package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.Locale

internal fun profileMatchesQuery(profile: PlatformV2, query: String): Boolean {
    val text = "${profile.name} ${profile.model} ${profile.compatibleType} ${profile.labels}".lowercase(Locale.ROOT)
    return query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).all { it in text }
}

@Composable
internal fun ProfileSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.profile_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}
