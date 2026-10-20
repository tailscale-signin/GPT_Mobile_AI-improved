package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatTypeScreen(
    features: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(),
    revealOrigin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Unspecified,
    onBack: () -> Unit,
    onChoose: (NewChatType) -> Unit
) {
    NewChatRevealPanel(revealOrigin) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("New Chat", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, titleContentColor = MaterialTheme.colorScheme.primary, navigationIconContentColor = MaterialTheme.colorScheme.primary)
                )
            }
        ) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets).testTag("new_chat_type_screen"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("STEP 1 OF 2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("What would you like to do?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text("Choose how your AI models will work together.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                    }
                }
                val options = dev.chungjungsoo.gptmobile.data.model.availableNewChatTypes(features)
                items(options, key = { it.name }) { option ->
                    if (option == options.firstOrNull { it.requiredPluginId != null }) {
                        Text("Plugins", Modifier.padding(top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    val icon: ImageVector = when (option) {
                        NewChatType.RESEARCH -> Icons.Rounded.Science
                        NewChatType.SINGLE -> Icons.AutoMirrored.Rounded.Chat
                        NewChatType.MULTI_CHAT -> Icons.Rounded.ViewColumn
                        NewChatType.COMBINED -> Icons.Rounded.Hub
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(option) }.testTag("new_chat_type_" + option.name),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            Modifier.padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(14.dp).size(28.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(option.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(option.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                if (option == NewChatType.RESEARCH) {
                                    Text("Deep Research on by default · Combined with 2+ profiles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
