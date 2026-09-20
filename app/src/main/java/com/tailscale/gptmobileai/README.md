# 📍 Location MCP Tool

## Overview
A complete location tracking service integrated into the GPT Mobile AI app using Android's FusedLocationProviderClient with Hilt dependency injection and Jetpack Compose UI.

## Features
- ✅ Real-time location tracking with configurable priority
- ✅ Permission management (fine/coarse location)
- ✅ Last known location retrieval
- ✅ Power-efficient updates via FusedLocationProviderClient
- ✅ State management with Kotlin Flow
- ✅ Beautiful Jetpack Compose UI

## Architecture
```
LocationMcpToolModule.kt  → Hilt DI module for dependencies
LocationViewModel.kt      → ViewModel with location state management
LocationMcpToolScreen.kt  → Compose UI for location display
```

## Usage

### In Code
```kotlin
@HiltViewModel
class YourViewModel @Inject constructor(
    private val locationViewModel: LocationViewModel
) : ViewModel() {

    init {
        // Start tracking with balanced power/accuracy
        locationViewModel.requestLocationUpdates(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            minUpdateIntervalMillis = 5000L
        )
    }
}
```

### In UI
```kotlin
@Composable
fun YourScreen() {
    val locationViewModel: LocationViewModel = viewModel()
    
    // Access current location state
    val state by locationViewModel.locationState.collectAsState()
    
    // Display last known location
    state.lastKnownLocation?.let { location ->
        Text("Lat: ${location.latitude}, Lon: ${location.longitude}")
    }
}
```

## Permissions
The app requires the following permissions in `AndroidManifest.xml`:
- `ACCESS_FINE_LOCATION` - Precise GPS location
- `ACCESS_COARSE_LOCATION` - Approximate network-based location

## Configuration Options

### Location Priority
- `PRIORITY_HIGH_ACCURACY` - Best accuracy, higher power consumption
- `PRIORITY_BALANCED_POWER_ACCURACY` - Balanced approach (default)
- `PRIORITY_LOW_POWER` - Minimal power consumption
- `PRIORITY_NO_POWER` - No location updates

### Update Interval
Configure minimum update interval in milliseconds:
```kotlin
requestLocationUpdates(
    priority = Priority.PRIORITY_HIGH_ACCURACY,
    minUpdateIntervalMillis = 3000L // 3 seconds
)
```

## State Management
The `LocationState` provides real-time updates:
- `lastKnownLocation` - Most recent location data
- `isTracking` - Whether location updates are active
- `error` - Any error messages
- `permissionGranted` - Permission status

## Data Model
```kotlin
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Float,
    val bearingDegrees: Float,
    val speedMetersPerSecond: Float,
    val timestamp: Long
)
```

## Best Practices
1. Always check permission status before requesting updates
2. Use `PRIORITY_BALANCED_POWER_ACCURACY` for most use cases
3. Stop tracking when no longer needed to save battery
4. Handle location errors gracefully in your UI
5. Clean up resources in ViewModel's `onCleared()`

## Testing
```kotlin
@Test
fun testLocationUpdates() {
    val viewModel = LocationViewModel(fusedLocationClient)
    
    // Grant permissions and verify state updates
    viewModel.requestLocationPermissions()
    viewModel.requestLocationUpdates()
}
```

## Troubleshooting
- **No location updates**: Check if GPS is enabled on device
- **Permission denied**: Request runtime permissions in your Activity
- **Accuracy issues**: Use `PRIORITY_HIGH_ACCURACY` for better precision
- **Battery drain**: Increase update interval or use lower priority

## References
- [FusedLocationProviderClient Documentation](https://developer.android.com/reference/com/google/android/gms/location/FusedLocationProviderClient)
- [Android Location Best Practices](https://developer.android.com/training/location/best-practices)
