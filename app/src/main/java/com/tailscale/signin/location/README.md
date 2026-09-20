# Location MCP Tool

A complete location tracking solution for GPT Mobile AI Improved using FusedLocationProviderClient and Jetpack Compose.

## Features

- ✅ Real-time location tracking with FusedLocationProviderClient
- ✅ Automatic permission management (fine/coarse)
- ✅ Configurable accuracy priority
- ✅ StateFlow reactive state management
- ✅ Material 3 Compose UI with live location display
- ✅ Battery-efficient updates

## Usage

### ViewModel Integration

```kotlin
private val locationViewModel: LocationViewModel by viewModels()

// Request permissions
locationViewModel.requestPermissions()

// Get last known location
locationViewModel.getLastKnownLocation()

// Start tracking with default priority
locationViewModel.requestLocationUpdates()

// Start tracking with custom priority
locationViewModel.requestLocationUpdates(
    priority = Priority.PRIORITY_HIGH_ACCURACY,
    minUpdateIntervalMillis = 5000L
)

// Stop tracking
locationViewModel.stopLocationUpdates()
```

### UI Integration

```kotlin
@Composable
fun LocationScreen() {
    val viewModel: LocationViewModel = hiltViewModel()
    val state by viewModel.locationState.collectAsState()
    // ... display location data
}
```

## Priority Options

- `PRIORITY_HIGH_ACCURACY` - Best accuracy, higher battery usage
- `PRIORITY_BALANCED_POWER_ACCURACY` (default) - Balanced approach
- `PRIORITY_LOW_POWER` - Minimal battery impact
- `PRIORITY_NO_POWER` - No location updates

## Permissions

Already configured in AndroidManifest.xml:
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

## Architecture

- **Hilt DI**: Dependency injection for ViewModel
- **FusedLocationProviderClient**: Google Play Services optimized location
- **StateFlow**: Reactive state management
- **Jetpack Compose**: Modern declarative UI
