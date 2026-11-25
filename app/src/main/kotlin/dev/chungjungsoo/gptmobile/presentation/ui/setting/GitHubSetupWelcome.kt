package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubSetupWelcome(
    onConnect: () -> Unit,
    onBack: () -> Unit,
    isConnecting: Boolean = false,
    error: String? = null,
    clientId: String? = null,
    onClientIdChange: (String) -> Unit = {},
    onManageConnections: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GitHubSetupOverview(
                title = "Connect your account",
                description = "Sign in with GitHub to choose a repository for your chat."
            )
            clientId?.let { value ->
                OutlinedTextField(
                    value = value,
                    onValueChange = onClientIdChange,
                    label = { Text("GitHub OAuth client ID") },
                    supportingText = { Text("Use the public client ID of your GitHub App with device flow enabled. Your password and client secret stay with GitHub.") },
                    enabled = !isConnecting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onConnect, enabled = !isConnecting, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(painterResource(R.drawable.ic_github_outline), null, Modifier.size(24.dp))
                Text(if (isConnecting) "Connecting…" else "Connect GitHub", Modifier.padding(start = 12.dp))
            }
            onManageConnections?.let { open ->
                TextButton(onClick = open, enabled = !isConnecting) { Text("Manage existing or enterprise connections") }
            }
        }
    }
}

@Composable
internal fun GitHubSetupOverview(title: String, description: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.surfaceContainerLow))).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(painterResource(R.drawable.ic_github_outline), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
