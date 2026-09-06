package dev.melo.gptmobile.improved.presentation.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.model.ClientType

data class PlatformTypeItem(
    val clientType: ClientType,
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val isPopular: Boolean = false,
    val isLocal: Boolean = false
)

private val PLATFORM_TYPES = listOf(
    PlatformTypeItem(
        clientType = ClientType.LITERT_LM,
        titleRes = R.string.platform_litert_title,
        descriptionRes = R.string.platform_litert_desc,
        icon = Icons.Default.PhoneAndroid,
        isPopular = true,
        isLocal = true
    ),
    PlatformTypeItem(
        clientType = ClientType.OPENAI,
        titleRes = R.string.platform_openai_title,
        descriptionRes = R.string.platform_openai_desc,
        icon = Icons.Default.AutoAwesome,
        isPopular = true
    ),
    PlatformTypeItem(
        clientType = ClientType.ANTHROPIC,
        titleRes = R.string.platform_anthropic_title,
        descriptionRes = R.string.platform_anthropic_desc,
        icon = Icons.Default.Psychology,
        isPopular = true
    ),
    PlatformTypeItem(
        clientType = ClientType.GOOGLE,
        titleRes = R.string.platform_google_title,
        descriptionRes = R.string.platform_google_desc,
        icon = Icons.Default.Cloud,
        isPopular = true
    ),
    PlatformTypeItem(
        clientType = ClientType.GROQ,
        titleRes = R.string.platform_groq_title,
        descriptionRes = R.string.platform_groq_desc,
        icon = Icons.Default.FlashOn
    ),
    PlatformTypeItem(
        clientType = ClientType.OLLAMA,
        titleRes = R.string.platform_ollama_title,
        descriptionRes = R.string.platform_ollama_desc,
        icon = Icons.Default.Computer
    ),
    PlatformTypeItem(
        clientType = ClientType.OPENROUTER,
        titleRes = R.string.platform_openrouter_title,
        descriptionRes = R.string.platform_openrouter_desc,
        icon = Icons.Default.Tune
    ),
    PlatformTypeItem(
        clientType = ClientType.CUSTOM,
        titleRes = R.string.platform_custom_title,
        descriptionRes = R.string.platform_custom_desc,
        icon = Icons.Default.Tune
    )
)

@Composable
fun SetupPlatformTypeScreen(
    modifier: Modifier = Modifier,
    setupViewModel: SetupViewModelV2 = hiltViewModel(),
    onSelectType: (ClientType) -> Unit,
    onBackAction: () -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SetupAppBar(backAction = onBackAction)
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.choose_platform_type),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.choose_platform_type_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(PLATFORM_TYPES) { item ->
                PlatformTypeCard(
                    item = item,
                    onClick = {
                        setupViewModel.selectClientType(item.clientType)
                        onSelectType(item.clientType)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlatformTypeCard(
    item: PlatformTypeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (item.isPopular) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (item.isLocal) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = stringResource(R.string.local_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (item.isPopular) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = stringResource(R.string.popular),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(item.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
