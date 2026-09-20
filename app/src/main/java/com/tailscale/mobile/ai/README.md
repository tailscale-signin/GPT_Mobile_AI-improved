# Location MCP Tool

A comprehensive location tracking tool for the GPT Mobile AI app using Android's FusedLocationProviderClient and Jetpack Compose.

## Features

- ✅ **Real-time Location Tracking** - Uses FusedLocationProviderClient for power-efficient updates
- ✅ **Permission Management** - Automatic fine/coarse location permission handling
- ✅ **Configurable Priority** - Choose between HIGH_ACCURACY, BALANCED_POWER_ACCURACY (default), LOW_POWER, NO_POWER
- ✅ **StateFlow State Management** - Reactive UI updates with Kotlin Flow
- ✅ **Material 3 Compose UI** - Modern design with live location display

## Usage

### In ViewModel
```kotlin
private val locationViewModel: LocationViewModel by viewModels()

// Request permissions
locationViewModel.requestPermissions()

// Get last known location
locationViewModel.getLastKnownLocation()

// Start tracking
locationViewModel.requestLocationUpdates(
    priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
    minUpdateIntervalMillis = 5000L
)

// Stop tracking
locationViewModel.stopLocationUpdates()
```

### In Compose UI
```kotlin
@Composable
fun LocationScreen() {
    val viewModel: LocationViewModel = viewModel()
    
    LocationMcpToolScreen(
        onNavigateBack = { /* handle back */ },
        viewModel = viewModel
    )
}
```

## Permissions

Already configured in AndroidManifest.xml:
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

## Architecture

- **Hilt DI**: Dependency injection for FusedLocationProviderClient
- **StateFlow**: Reactive state management
- **FusedLocationProviderClient**: Google Play Services location API
- **Jetpack Compose**: Modern declarative UI

## Files

| File | Description |
|------|-------------|
| `LocationViewModel.kt` | Core location logic with Hilt DI, FusedLocationProviderClient, StateFlow |
| `LocationMcpToolModule.kt` | Dependency injection module for location services |
| `LocationMcpToolScreen.kt` | Material 3 Compose UI with real-time location display |
| `README.md` | Complete documentation and usage examples |

## Branch
- **Branch**: `feature/location-mcp-tool`
- **Status**: Ready for testing and integration
