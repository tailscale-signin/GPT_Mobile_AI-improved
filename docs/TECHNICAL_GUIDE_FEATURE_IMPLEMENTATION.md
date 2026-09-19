# Technical Guide: GPT Mobile AI - Feature Implementation

## Overview
This guide documents three feature implementations for the GPT Mobile AI improved repository:

1. **Swipe Gesture Icons** - Show icons only during swipe on conversation cards
2. **Llama Platform Router Mode & Model Picker** - Fix router mode and dropdown fetch
3. **Backup System** - Include advanced settings, favourites, with exactly 2 options

---

## Feature 1: Swipe Gesture Icons on Conversation Cards

### Problem Statement
Icons appear at rest instead of only during swipe gestures on conversation cards. The main menu should remain clean when not interacting.

### Solution Architecture

#### Core Components
```kotlin
// State management for swipe visibility
val isSwiping = remember { mutableStateOf(false) }

// SwipeToDismissBox wrapper with conditional content
SwipeToDismissBox(
    state = swipeState,
    backgroundContent = when (swipeState.targetState) {
        SwipeStateSettleTarget.End -> ArchiveBackground()
        SwipeStateSettleTarget.Start -> DeleteBackground()
        else -> null
    },
    onDismiss = { /* handle dismiss */ }
) {
    // Card content with conditional icon display
}
```

#### Implementation Details

**Swipe State Detection:**
- Use `remember` to maintain swipe state across recompositions
- Track swipe start/end events via `onStart`, `onEnd` callbacks
- Toggle `isSwiping` boolean on gesture begin/end

**Conditional Icon Rendering:**
```kotlin
if (isSwiping.value) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Icon(Icons.Default.Archive, contentDescription = "Archive", tint = Color.Gray)
        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
    }
} else {
    // Clean card at rest - no icons
    Text(text = conversation.title ?: "New Chat")
}
```

**Active Run Indicator:**
- Keep active generation spinner always visible (per README feature)
- Separate from swipe gesture indicators

### Key Considerations
- **Performance**: Use `remember` to avoid unnecessary recompositions
- **Accessibility**: Provide content descriptions for swipe icons
- **Edge Cases**: Handle rapid swipe start/stop gracefully
- **Regression**: Preserve existing active run indicator functionality

---

## Feature 2: Llama Platform Router Mode & Model Picker Dropdown

### Problem Statement
Llama platform doesn't work with router mode, and model picker dropdown isn't fetching models.

### Solution Architecture

#### Core Components
```kotlin
// Platform settings with router mode toggle
@Composable
fun PlatformSettings(
    platform: AiPlatform,
    onModelSelect: (String) -> Unit,
    // ... other params
) {
    val models = remember(platform.id) { mutableStateOf(listOf<AiModel>()) }

    LaunchedEffect(platform.id) {
        chatRepository.getModels(platform.id).collect { fetchedModels ->
            models.value = fetchedModels
        }
    }
}
```

#### Router Mode Implementation

**Platform Data Model:**
```kotlin
data class AiPlatform(
    val id: String,
    val name: String,
    val useRouter: Boolean = false,  // Router mode flag
    // ... other fields
)
```

**Router Mode Behavior:**
- When `useRouter` is true, platform routes through intermediate service
- Model picker must fetch models from router endpoint
- Ensure `LaunchedEffect` triggers on router mode change

#### Model Picker Dropdown Fix

**Dropdown Implementation:**
```kotlin
ExposedDropdownMenuBox(
    expanded = modelPickerExpanded,
    onExpandedChange = { modelPickerExpanded = it }
) {
    OutlinedTextField(
        value = selectedModelName,
        onValueChange = {},
        readOnly = true,
        modifier = Modifier.fillMaxWidth().menuAnchor(),
        trailingIcon = {
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelPickerExpanded)
        }
    )

    ExposedDropdownMenu(
        expanded = modelPickerExpanded,
        onDismissRequest = { modelPickerExpanded = false }
    ) {
        models.value.forEach { model ->
            DropdownMenuItem(
                text = { Text(model.name) },
                onClick = {
                    selectedModelName = model.name
                    onModelSelect(model.id)
                    modelPickerExpanded = false
                }
            )
        }
    }
}
```

**Fetch Trigger Points:**
- Platform ID change (`LaunchedEffect(platform.id)`)
- Router mode toggle (`LaunchedEffect(platform.useRouter)`)
- Dropdown open event (optional, for real-time updates)

### Key Considerations
- **State Synchronization**: Ensure router mode changes trigger model refetch
- **Error Handling**: Handle empty model lists gracefully
- **Loading States**: Show loading indicator during fetch
- **Dropdown UX**: Prevent dropdown from closing when clicking outside

---

## Feature 3: Backup System with Advanced Settings & Favourites

### Problem Statement
Backup doesn't include advanced settings, favourites aren't in chat conversation backup, and there should only be 2 options.

### Solution Architecture

#### Core Components

**1. Backup Data Model**
```kotlin
data class BackupData(
    val version: String = "1.0",
    val timestamp: Long = System.currentTimeMillis(),
    val conversations: List<Chat> = emptyList(),
    val favourites: List<String> = emptyList(),
    val advancedSettings: Map<String, Any?> = emptyMap(),
    val generalSettings: Map<String, String> = emptyMap()
)
```

**2. Backup Manager Repository**
```kotlin
class BackupManager(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository
) {
    // Two backup options only
    suspend fun createBackup(): Result<BackupData> {
        return try {
            val conversations = chatRepository.getAllConversations()
            val favourites = settingsRepository.getFavourites()
            val advancedSettings = settingsRepository.getAdvancedSettings()
            val generalSettings = settingsRepository.getGeneralSettings()
            
            BackupData(
                conversations = conversations,
                favourites = favourites,
                advancedSettings = advancedSettings,
                generalSettings = generalSettings
            ).let { Result.success(it) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun restoreBackup(backupData: BackupData): Result<Unit> {
        return try {
            chatRepository.clearAllConversations()
            chatRepository.addConversations(backupData.conversations)
            settingsRepository.setFavourites(backupData.favourites)
            settingsRepository.setAdvancedSettings(backupData.advancedSettings)
            settingsRepository.setGeneralSettings(backupData.generalSettings)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getBackupOptions(): List<BackupOption> = listOf(
        BackupOption("Create Backup", "Backup all data including conversations, favourites, and advanced settings"),
        BackupOption("Restore Backup", "Restore from a previously created backup file")
    )
}
```

**3. Settings Repository Extensions**
```kotlin
class SettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    // Favourites methods
    suspend fun getFavourites(): List<String> = 
        dataStore.data.map { it.getStringSet("favourites", emptySet()).orEmpty() }.first()
    
    suspend fun setFavourites(favourites: List<String>) = 
        dataStore.edit { it.putStringSet("favourites", favourites.toSet()) }
    
    // Advanced settings methods
    suspend fun getAdvancedSettings(): Map<String, Any?> = 
        dataStore.data.map { 
            it.asMap().mapValues { (key, value) -> 
                when (value) {
                    is String -> value
                    is Int -> value
                    is Boolean -> value
                    else -> null
                }
            }.orEmpty() 
        }.first()
    
    suspend fun setAdvancedSettings(settings: Map<String, Any?>) = 
        dataStore.edit { preferences ->
            settings.forEach { (key, value) ->
                when (value) {
                    is String -> preferences.putString(key, value)
                    is Int -> preferences.putInt(key, value)
                    is Boolean -> preferences.putBoolean(key, value)
                }
            }
        }
}
```

**4. Backup UI with Exactly 2 Options**
```kotlin
@Composable
fun BackupSettingsScreen(
    backupManager: BackupManager,
    onRestoreSuccess: () -> Unit,
    onRestoreFailure: (String) -> Unit
) {
    val selectedOption by remember { mutableStateOf<BackupOption?>(null) }
    val isRestoring by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "Backup & Restore", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)

        // Option 1: Create Backup
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedOption?.title == "Create Backup") 
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Create Backup", style = MaterialTheme.typography.titleMedium)
                Text(text = "Backup all data including conversations, favourites, and advanced settings")
                Button(onClick = { selectedOption = backupManager.getBackupOptions().find { it.title == "Create Backup" } }) {
                    Text("Create Backup")
                }
            }
        }

        // Option 2: Restore Backup
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedOption?.title == "Restore Backup") 
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Restore Backup", style = MaterialTheme.typography.titleMedium)
                Text(text = "Restore from a previously created backup file")
                Button(onClick = { selectedOption = backupManager.getBackupOptions().find { it.title == "Restore Backup" } }) {
                    Text("Restore Backup")
                }
            }
        }

        // Restore dialog and progress handling...
    }
}
```

### Key Considerations
- **Data Integrity**: Ensure all data types are properly serialized/deserialized
- **Versioning**: Include backup version for future compatibility checks
- **File Storage**: Use `FileProvider` for secure file sharing
- **Error Handling**: Show user-friendly error messages on failure
- **UI Simplicity**: Exactly 2 options as specified, no additional choices

---

## Integration Checklist

### Swipe Gesture Feature
- [ ] Wrap conversation cards in `SwipeToDismissBox`
- [ ] Add swipe state tracking with `remember`
- [ ] Conditionally render icons only when `isSwiping.value` is true
- [ ] Preserve active run indicator visibility
- [ ] Test rapid swipe start/stop scenarios

### Llama Platform Feature
- [ ] Add `useRouter` boolean to `AiPlatform` data class
- [ ] Ensure `LaunchedEffect` triggers on router mode change
- [ ] Fix model picker dropdown fetch logic
- [ ] Handle empty model lists gracefully
- [ ] Test both direct and router modes

### Backup System Feature
- [ ] Create `BackupData` model with all required fields
- [ ] Implement `BackupManager` with create/restore methods
- [ ] Add favourites to backup data
- [ ] Include advanced settings in backup
- [ ] Ensure UI shows exactly 2 options only
- [ ] Test full backup/restore cycle

---

## Testing Recommendations

1. **Swipe Gesture:**
   - Swipe conversation cards left/right
   - Verify icons appear only during swipe
   - Confirm main menu remains clean at rest
   - Test with active generation running

2. **Llama Platform:**
   - Toggle router mode on/off
   - Verify model picker fetches models in both modes
   - Test dropdown open/close behavior
   - Check for empty state handling

3. **Backup System:**
   - Create backup and verify all data included
   - Restore backup and verify data integrity
   - Confirm only 2 options appear in UI
   - Test with various data sizes

---

## Dependencies Required

- Jetpack Compose: `androidx.compose.material3:material3`
- Android DataStore: `androidx.datastore:datastore-preferences`
- SwipeToDismissBox: Built into Compose Material 3

No additional dependencies needed for these features.
