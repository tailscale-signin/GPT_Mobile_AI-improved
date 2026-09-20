# Location MCP Tool

A complete location service implementation for the GPT Mobile AI app using Android's FusedLocationProviderClient.

## Features

- **Last Known Location**: Retrieve the most recent cached location
- **Single Location Request**: Get a fresh location update on demand
- **Continuous Updates**: Start/stop real-time location tracking with configurable intervals
- **Permission Management**: Handle runtime location permissions gracefully
- **State Flow Integration**: Reactive state management via Kotlin Flow
- **Compose UI**: Ready-to-use Jetpack Compose component

## Architecture

```
LocationModule (Hilt DI)
    └── LocationService (FusedLocationProviderClient)
        └── LocationViewModel (MVVM layer)
            └── LocationMcpTool (UI component)
```

## Usage

### In ViewModel/Repository
```kotlin
val locationService: LocationService = hiltInject()

// Get last known location
val location = locationService.getLastKnownLocation()

// Start continuous updates
locationService.startLocationUpdates(
    minUpdateIntervalMillis = 10_000,
    maxUpdateDistanceMeters = 10f
)

// Stop updates
locationService.stopLocationUpdates()
```

### In Compose UI
```kotlin
@Composable
fun LocationScreen() {
    val viewModel: LocationViewModel = hiltViewModel()
    LocationMcpTool(viewModel = viewModel)
}
```

## Permissions

The AndroidManifest.xml already includes the required permissions:
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

## Dependencies

No new dependencies required. Uses existing:
- Hilt for dependency injection
- Kotlin Coroutines Flow
- Jetpack Compose (UI)
- Android Location APIs

## Testing

```kotlin
@Test
fun testLocationService() = runTest {
    val service = locationService
    val location = service.getLastKnownLocation()
    assertNotNull(location)
}
```
