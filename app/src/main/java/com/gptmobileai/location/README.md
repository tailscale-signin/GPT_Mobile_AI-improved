# Location MCP Tool

A complete location tracking solution for GPT Mobile AI Improved, built with Android best practices.

## 🚀 Features

- **Real-time Location Tracking** using FusedLocationProviderClient
- **Battery Optimized** with configurable priority levels
- **Permission Management** with automatic handling
- **Jetpack Compose UI** with Material 3 design
- **Kotlin StateFlow** for reactive state management
- **Hilt Dependency Injection** for clean architecture

## 📋 Requirements

- Android API 21+
- Google Play Services (for FusedLocationProviderClient)
- Location permissions in AndroidManifest.xml

## 🔧 Setup

### 1. Add Dependencies

```gradle
// app/build.gradle.kts
implementation("com.google.android.gms:play-services-location:21.0.1")
dagger.hilt.android.lifecycle.HiltViewModel
```

### 2. Permissions (Already configured)

The AndroidManifest.xml already includes:
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

## 📱 Usage

### ViewModel Integration

```kotlin
class YourActivity : AppCompatActivity() {
    private val locationViewModel: LocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request permissions
        locationViewModel.requestPermissions()
        
        // Get last known location
        locationViewModel.getLastKnownLocation()
        
        // Start tracking
        locationViewModel.requestLocationUpdates(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
        )
    }
}
```

### Compose UI Integration

```kotlin
@Composable
fun YourScreen() {
    LocationMcpToolScreen(
        onPermissionGranted = { /* Handle permission granted */ }
    )
}
```

## ⚙️ Configuration Options

### Priority Levels

| Priority | Description | Battery Impact |
|----------|-------------|----------------|
| `HIGH_ACCURACY` | GPS + Network | High |
| `BALANCED_POWER_ACCURACY` | GPS + Wi-Fi + Cell | Medium (Default) |
| `LOW_POWER` | Network only | Low |
| `NO_POWER` | No updates | None |

### Update Interval

Configurable in milliseconds (default: 5000ms / 5 seconds)

## 📊 Location Data

The tool provides:
- **Latitude** - Geographic coordinate
- **Longitude** - Geographic coordinate
- **Altitude** - Elevation above sea level (meters)
- **Accuracy** - Estimated accuracy in meters
- **Timestamp** - When the location was captured

## 🎨 UI Components

- Permission status indicator (color-coded)
- Real-time location display card
- Error handling with visual feedback
- Start/Stop tracking buttons
- One-tap last known location retrieval

## 🔒 Privacy & Security

- No location data is stored permanently
- All operations happen in memory
- User must grant explicit permission
- Location updates can be stopped at any time

## 🧪 Testing

```kotlin
@Test
fun testLocationViewModel() {
    val viewModel = LocationViewModel(context)
    
    // Test getting last known location
    viewModel.getLastKnownLocation()
    
    // Verify state updates
    val state = viewModel.locationState.value
    assertNotNull(state.lastKnownLocation)
}
```

## 📝 Architecture

```
┌─────────────────┐
│  LocationScreen │  ← Jetpack Compose UI
├─────────────────┤
│  LocationVM     │  ← ViewModel with StateFlow
├─────────────────┤
│  FusedLocation  │  ← Google Play Services
│  ProviderClient │
└─────────────────┘
```

## 🛠️ Troubleshooting

### "No location available"
- Ensure device has GPS enabled
- Check location services are on
- Verify permissions are granted

### Permission denied
- Use `ActivityResultContracts.RequestPermission` for modern Android
- Handle `shouldShowRequestPermissionRationale`

### Battery drain
- Use `PRIORITY_LOW_POWER` for background apps
- Increase update interval
- Stop updates when not needed

## 📄 License

Part of GPT Mobile AI Improved project.
