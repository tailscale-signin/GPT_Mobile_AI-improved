package dev.chungjungsoo.gptmobile.presentation.ui.startscreen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.PrimaryLongButton
import dev.chungjungsoo.gptmobile.presentation.icons.GptMobileStartScreen

@Composable
fun StartScreen(onStartClick: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                IntroHero()
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IntroFeatureCard(
                        icon = Icons.Default.SmartToy,
                        title = "Connect your AI",
                        description = "Add cloud providers, local runtimes, or both. Provider connections stay separate from the AI profiles that use them."
                    )
                    IntroFeatureCard(
                        icon = Icons.Default.Memory,
                        title = "Choose models your way",
                        description = "Discover provider models, manage on-device models, and search compatible Hugging Face downloads from one app."
                    )
                    IntroFeatureCard(
                        icon = Icons.Default.Hub,
                        title = "Give profiles the right tools",
                        description = "Assign built-in tools and remote MCP connections per profile, with connection health and permissions kept visible."
                    )
                    IntroFeatureCard(
                        icon = Icons.Default.Security,
                        title = "Stay in control",
                        description = "Background work, location, diagnostics, backups, notifications, and fallbacks can all be controlled from Advanced Settings."
                    )
                }
            }
            item {
                FirstRunRoadmap()
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 24.dp)
                ) {
                    PrimaryLongButton(
                        onClick = onStartClick,
                        text = stringResource(R.string.get_started)
                    )
                    Text(
                        text = "You can change providers, profiles, models and tools later from Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroHero() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            ) {
                Box(
                    modifier = Modifier.size(148.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        imageVector = GptMobileStartScreen,
                        contentDescription = stringResource(R.string.gpt_mobile_introduction_logo),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(124.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Your AI workspace, your way",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Bring cloud AI, local models and MCP tools together without hiding where each capability comes from.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun IntroFeatureCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun FirstRunRoadmap() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Setup takes three quick steps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        RoadmapLine("1", "Choose a provider or local runtime")
        RoadmapLine("2", "Pick or discover a model")
        RoadmapLine("3", "Name the AI profile and start chatting")
    }
}

@Composable
private fun RoadmapLine(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
