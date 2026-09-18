# Unified Model Picker & Message Queue Implementation

## Overview

This branch implements two major features for the GPT Mobile AI app:

1. **Unified Model Picker** - A single screen to select models from all providers (Ollama, OpenRouter, Local)
2. **Message Queue System** - Priority-based queue management for AI requests with retry logic

## Architecture

### Domain Layer (`app/domain/`)

#### Models (`UnifiedModelPicker.kt`)
- `UnifiedModel` - Represents a model across providers with metadata
- `MessageQueueStatus` - Sealed class for queue states (Pending, Processing, Completed, Error)
- `QueuedMessage` - Message queue item with priority and retry tracking
- `QueueConfig` - Configuration for queue behavior
- Repository interfaces: `MessageQueueRepository`, `ModelRepository`, `ModelPickerRepository`

#### Use Cases (`UnifiedModelPickerUseCases.kt`)
- **EnqueueMessageUseCase** - Add messages to the queue
- **ProcessQueueUseCase** - Process next message in queue
- **GetQueueStatusUseCase** - Retrieve current queue state
- **ClearCompletedMessagesUseCase** - Remove completed messages
- **GetActiveModelUseCase** - Get currently selected model
- **SetDefaultModelUseCase** - Set default model
- **GetModelsByProviderUseCase** - Filter models by provider
- **FilterModelsUseCase** - Search/filter models

#### Message Queue Manager
- Orchestrates queue operations with automatic retry logic
- Exposes `StateFlow<List<QueuedMessage>>` for reactive UI updates
- Handles priority-based processing and exponential backoff

### Data Layer (`app/data/`)

#### In-Memory Repositories (`InMemoryRepositories.kt`)
- `InMemoryMessageQueueRepository` - Thread-safe in-memory queue implementation
- `InMemoryModelRepository` - Pre-seeded with sample models from all providers
- `InMemoryModelPickerRepository` - Manages active model selection

### Presentation Layer (`app/presentation/`)

#### UI Components

**Unified Model Picker Screen** (`UnifiedModelPickerScreen.kt`)
- Searchable model list with provider filtering
- Visual indicators for default and active models
- Provider color coding (Ollama: Purple, OpenRouter: Blue, Local: Green)
- Context window and temperature metadata display

**Message Queue Screen** (`MessageQueueScreen.kt`)
- Real-time queue statistics card
- Priority badge system (P1-P5 with color coding)
- Status badges (Pending, Processing, Completed, Error)
- Retry functionality with retry count tracking
- Clear completed messages action
- Priority selection dialog

#### ViewModel (`UnifiedModelPickerViewModel.kt`)
- Manages UI state and user interactions
- Auto-processes queue every 5 seconds
- Handles error states and loading states
- Coordinates between domain use cases and UI

## Key Features

### Unified Model Picker
- ✅ Single source for all models across providers
- ✅ Search and filter by name/description
- ✅ Persistent model selection
- ✅ Provider filtering capability
- ✅ Visual model cards with metadata
- ✅ Default model management

### Message Queue System
- ✅ Priority-based processing (1-5 scale)
- ✅ Automatic retry with exponential backoff
- ✅ Status tracking (Pending → Processing → Completed/Error)
- ✅ Retry count and max retries configuration
- ✅ Real-time queue state updates via StateFlow
- ✅ Clear completed messages functionality
- ✅ Auto-processing every 5 seconds

## Usage

### Enqueue a Message
```kotlin
viewModel.enqueueMessage(
    content = "Analyze this data",
    modelId = "ollama-llama3",
    priority = 4 // High priority
)
```

### Process Queue Manually
```kotlin
viewModel.processNext()
```

### Select a Model
```kotlin
viewModel.selectModel("openrouter-gpt4o")
```

### Retry Failed Message
```kotlin
viewModel.retryMessage(messageId)
```

## Configuration

The `QueueConfig` allows customization:
- `maxQueueSize`: Maximum queue capacity (default: 100)
- `defaultPriority`: Default message priority (default: 3)
- `maxRetries`: Maximum retry attempts (default: 3)
- `retryDelayMs`: Initial delay between retries (default: 1000ms)
- `exponentialBackoffMultiplier`: Backoff multiplier (default: 2.0)

## Testing

The in-memory repositories provide a foundation for unit testing:
```kotlin
@Test
fun testMessageEnqueueAndProcess() {
    val repository = InMemoryMessageQueueRepository()
    val message = QueuedMessage(content = "Test", modelId = "test-model")
    
    val id = repository.enqueue(message)
    val processed = repository.dequeue()
    
    assertEquals("Test", processed?.content)
}
```

## Next Steps

1. **Integrate with existing chat flow** - Connect message queue to the main chat UI
2. **Add persistence** - Implement database storage (Room/SQLite) for repositories
3. **Network resilience** - Add network retry logic integration
4. **Analytics** - Track queue performance and model selection patterns
5. **Notifications** - Notify users when high-priority messages are processed

## Files Created

- `app/domain/src/main/java/com/gptmobileai/domain/model/UnifiedModelPicker.kt`
- `app/domain/src/main/java/com/gptmobileai/domain/usecase/UnifiedModelPickerUseCases.kt`
- `app/data/src/main/java/com/gptmobileai/data/repository/InMemoryRepositories.kt`
- `app/presentation/src/main/java/com/gptmobileai/presentation/ui/modelpicker/UnifiedModelPickerScreen.kt`
- `app/presentation/src/main/java/com/gptmobileai/presentation/ui/messagequeue/MessageQueueScreen.kt`
- `app/presentation/src/main/java/com/gptmobileai/presentation/viewmodel/UnifiedModelPickerViewModel.kt`

## Branch Information

- **Branch**: `feat/unified-model-picker-and-message-queue-v2`
- **Base**: `860d290da984c44e8853921a9373ad69b5bae89f` (main)
- **Status**: Implementation complete, ready for integration testing
