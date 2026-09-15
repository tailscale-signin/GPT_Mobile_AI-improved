# OpenRouter Credits Display Feature

## Overview

This feature adds a fancy, animated credits display box that shows OpenRouter remaining credits, visible only when OpenRouter is configured as the AI platform.

## Implementation

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Main Application                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           OpenRouterCreditsService (DI Module)               │
│  - Fetch credits from API using management key               │
│  - Cache results to reduce API calls                         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│           CreditsViewModel (ViewModel)                       │
│  - Manage credits state (Loading/Success/Error)              │
│  - Handle API responses and errors                           │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│          CreditsBox (Composable UI Component)                │
│  - Fancy gradient design                                     │
│  - Animated progress bar                                     │
│  - Status indicators (Available/Low)                         │
│  - Responsive layout                                         │
└─────────────────────────────────────────────────────────────┘
```

### Files Added

1. **Data Layer**
   - `data/openrouter/OpenRouterCredits.kt` - Data models for credits API
   - `data/openrouter/OpenRouterCreditsService.kt` - API service with caching

2. **Presentation Layer**
   - `presentation/ui/common/CreditsBox.kt` - Composable UI component
   - `presentation/ui/common/CreditsViewModel.kt` - ViewModel for state management

3. **Dependency Injection**
   - `di/OpenRouterModule.kt` - Provides OpenRouterCreditsService
   - `di/ViewModelModule.kt` - Provides CreditsViewModel

## Usage

### Configuration

Add the OpenRouter management key to your credentials:

```kotlin
// In your credential manager
secretRepository.saveSecret("openrouter_management_key", "your-management-key")
```

### Display Credits

```kotlin
@Composable
fun ShowCredits() {
    val viewModel: CreditsViewModel = hiltViewModel()
    val creditsState by viewModel.creditsState.collectAsState()

    when (creditsState) {
        is CreditsState.Loading -> {
            CircularProgressIndicator()
        }
        is CreditsState.Success -> {
            CreditsBox(creditsData = (creditsState as CreditsState.Success).credits)
        }
        is CreditsState.Error -> {
            Text("Error: ${creditsState.message}")
        }
    }
}
```

### Fetch Credits

```kotlin
// Fetch credits (uses cache if available)
viewModel.fetchCredits()

// Force refresh from API
viewModel.fetchCredits(forceRefresh = true)
```

## Features

- ✅ **Only visible when OpenRouter is configured** - Checks for management key
- ✅ **Shows remaining credits** - Displays `total_credits - total_usage`
- ✅ **Uses management key** - Requires OpenRouter management key for API access
- ✅ **Fancy UI design** - Gradient background, glassmorphism effect
- ✅ **Animated progress bar** - Visual representation of credit usage
- ✅ **Caching** - Reduces API calls with configurable TTL
- ✅ **Error handling** - Graceful fallback on errors
- ✅ **Status indicators** - Shows "Available" or "Low credits"
- ✅ **Responsive** - Works on all screen sizes

## API Details

### Endpoint
```
GET https://openrouter.ai/api/v1/credits
Authorization: Bearer <management-key>
```

### Response Format
```json
{
  "data": {
    "total_credits": 100.0,
    "total_usage": 25.5
  }
}
```

### Calculated Fields
- `remaining` = `total_credits - total_usage`
- `usage_percentage` = `(total_usage / total_credits) * 100`

## Styling

The credits box features:
- **Gradient background** - Purple gradient for available credits, red for low credits
- **Animated progress bar** - Green (0-70%), yellow (70-90%), red (90-100%)
- **Glassmorphism effect** - Modern, translucent appearance
- **Responsive padding** - Adapts to different screen sizes

## Testing

### Unit Tests
```kotlin
@Test
fun `fetch credits returns success when API key is valid`() = runTest {
    val viewModel = CreditsViewModel(creditsService, secretRepository)
    viewModel.fetchCredits()
    
    val state = viewModel.creditsState.value
    assertTrue(state is CreditsState.Success)
}
```

### Manual Testing
1. Configure OpenRouter management key
2. Open the app
3. Verify credits box appears
4. Check remaining credits display correctly
5. Verify progress bar reflects usage percentage
6. Test error handling by removing API key

## Future Enhancements

- [ ] Add refresh interval setting
- [ ] Add notification when credits are low
- [ ] Add credits history tracking
- [ ] Add support for multiple credit sources
- [ ] Add dark/light theme support

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
