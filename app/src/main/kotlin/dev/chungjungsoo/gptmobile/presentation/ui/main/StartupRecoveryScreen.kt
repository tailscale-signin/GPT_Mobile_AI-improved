package dev.chungjungsoo.gptmobile.presentation.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.DetailsSection

@Composable
internal fun StartupRecoveryScreen(detail: String, retry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.audit_startup_failed), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.audit_recovery_preserved))
        Button(onClick = retry) { Text(stringResource(R.string.retry)) }
        DetailsSection {
            Text(detail, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, detail)
                context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.audit_export_details)))
            }) { Text(stringResource(R.string.audit_export_details)) }
        }
    }
}
