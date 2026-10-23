package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.chungjungsoo.gptmobile.R

@Composable
fun DetailsSection(title: String = stringResource(R.string.audit_details), content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.audit_hide_details, title) else title) }
        if (expanded) content()
    }
}
