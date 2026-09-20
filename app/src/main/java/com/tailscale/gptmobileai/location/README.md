# Location MCP Tool

A comprehensive location tracking tool for GPT Mobile AI Improved, built with Android best practices.

## Features

- **Real-time Location Tracking** - Uses FusedLocationProviderClient for power-efficient updates
- **Permission Management** - Automatic fine/coarse location permission handling
- **Configurable Priority** - Choose between HIGH_ACCURACY, BALANCED_POWER_ACCURACY (default), LOW_POWER, NO_POWER
- **State Management** - Kotlin StateFlow for reactive UI updates
- **Compose UI** - Modern Material 3 design with live location display

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
```

### In Compose UI
```kotlin
@Composable
fun LocationScreen() {
    val viewModel: LocationViewModel = hiltViewModel()
    val state by viewModel.locationState.collectAsState()
    
    // Display location data
}
```

## Configuration Options

- **Priority**: `HIGH_ACCURACY`, `BALANCED_POWER_ACCURACY` (default), `LOW_POWER`, `NO_POWER`
- **Update Interval**: Configurable in milliseconds (default: 5000ms)

## Architecture

- **Hilt DI** - Dependency injection for FusedLocationProviderClient
- **ViewModel** - State management with Kotlin Flow
- **Compose UI** - Declarative UI with Material 3 components
- **Lifecycle Aware** - Proper cleanup on ViewModel destruction

## Permissions

The app requires location permissions in AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

## Testing

1. Grant location permission when prompted
2. Call `requestLocationUpdates()` to start tracking
3. Observe real-time updates in the UI
4. Call `removeLocationUpdates()` to stop tracking

## License

MIT License - See LICENSE file for details.
