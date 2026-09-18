package com.gptmobileai.presentation.ui.modelpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gptmobileai.domain.model.ModelProvider
import com.gptmobileai.domain.model.UnifiedModel

/**
 * Unified Model Picker Screen
 * 
 * Allows users to select models from all providers in one place
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedModelPickerScreen(
    modifier: Modifier = Modifier,
    onModelSelected: (String) -> Unit,
    onProviderFilterClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit
) {
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showProviderFilter by remember { mutableStateOf(false) }
    
    val models = remember { 
        listOf(
            UnifiedModel(
                id = "ollama-llama3",
                name = "Llama 3",
                provider = ModelProvider.Ollama,
                description = "Meta's latest LLM - Fast and capable",
                contextWindow = 8192,
                temperature = 0.7f,
                isDefault = true,
                isActive = true
            ),
            UnifiedModel(
                id = "ollama-mistral",
                name = "Mistral",
                provider = ModelProvider.Ollama,
                description = "Lightweight and efficient for local use",
                contextWindow = 32000,
                temperature = 0.5f,
                isActive = true
            ),
            UnifiedModel(
                id = "openrouter-gpt4o",
                name = "GPT-4o",
                provider = ModelProvider.OpenRouter,
                description = "OpenAI's multimodal model - Premium",
                contextWindow = 128000,
                temperature = 0.7f,
                isActive = true
            ),
            UnifiedModel(
                id = "openrouter-claude3",
                name = "Claude 3",
                provider = ModelProvider.OpenRouter,
                description = "Anthropic's reasoning powerhouse",
                contextWindow = 200000,
                temperature = 0.6f,
                isActive = true
            ),
            UnifiedModel(
                id = "local-codellama",
                name = "Code Llama",
                provider = ModelProvider.Local,
                description = "Specialized for code generation",
                contextWindow = 16384,
                temperature = 0.3f,
                isActive = true
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Picker") },
                actions = {
                    IconButton(onClick = onProviderFilterClick) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter by provider")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search models...") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Filter by name or description") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Model List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isSelected = selectedModelId == model.id,
                        onClick = { 
                            selectedModelId = model.id
                            onModelSelected(model.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: UnifiedModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Provider Icon/Color
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when (model.provider) {
                            ModelProvider.Ollama -> Color(0xFF7C3AED)
                            ModelProvider.OpenRouter -> Color(0xFF0EA5E9)
                            ModelProvider.Local -> Color(0xFF10B981)
                        }
                    )
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Model Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                        else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = model.description ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row {
                    Chip(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = { },
                        label = { Text(model.provider.getProviderName()) }
                    )
                    
                    if (model.isDefault) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Default",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
