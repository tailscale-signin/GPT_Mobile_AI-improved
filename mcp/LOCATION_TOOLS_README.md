# Location Tools - GPT Mobile AI MCP Server

## Overview

This branch adds **3 new location-based tools** to the GPT Mobile AI MCP server:

| Tool | Description | API Used | Rate Limit |
|------|-------------|----------|------------|
| `geolocate_ip` | Look up IP geolocation | ipapi.is (keyless) | 1000 req/day free tier |
| `reverse_geocode` | Lat/lon → address | OpenStreetMap Nominatim | 1 request/sec |
| `get_current_location` | GPS coordinates from device | ⚠️ Requires native bridge | — |

## Installation & Usage

### 1. Install Dependencies
```bash
cd mcp
npm install
```

### 2. Run the Server
```bash
node server.js
```

### 3. Configure MCP Client (`.vscode/mcp.json`)
```json
{
  "mcpServers": {
    "gpt-mobile-mcp": {
      "command": "node",
      "args": ["mcp/server.js"],
      "env": {},
      "workingDirectory": "."
    }
  }
}
```

## Tool Details

### `geolocate_ip` - IP Geolocation Lookup

**Purpose:** Get location data (country, city, timezone) for an IP address.

**Parameters:**
- `ip` (optional): IP address to lookup. If omitted, auto-detects the host's public IP.

**Example Usage:**
```javascript
// Auto-detect host IP
await callTool('geolocate_ip', {});

// Lookup specific IP
await callTool('geolocate_ip', { ip: '8.8.8.8' });
```

**Response Example:**
```json
{
  "ip": "203.0.113.45",
  "country_name": "United States",
  "country_code": "US",
  "region": "California",
  "city": "San Francisco",
  "zip": "94102",
  "timezone": "America/Los_Angeles",
  "latitude": 37.7749,
  "longitude": -122.4194
}
```

### `reverse_geocode` - Coordinates to Address

**Purpose:** Convert latitude/longitude coordinates to human-readable addresses.

**Parameters:**
- `latitude` (required): Latitude (-90 to 90)
- `longitude` (required): Longitude (-180 to 180)
- `format` (optional): Output format - `"json"` (default), `"address"`, or `"text"`

**Example Usage:**
```javascript
// JSON output (default)
await callTool('reverse_geocode', { latitude: 37.7749, longitude: -122.4194 });

// Address string
await callTool('reverse_geocode', { 
  latitude: 37.7749, 
  longitude: -122.4194,
  format: 'address' 
});
```

**Response Example (format: "address"):**
```
San Francisco, California 94102, United States of America
```

### `get_current_location` - GPS from Device

**Purpose:** Get real-time GPS coordinates from the Android/iOS device.

**⚠️ Important:** This tool currently returns a placeholder message because **GPS access requires a native companion service**. The MCP server runs on the host, not inside the app.

**To enable real GPS access, see: [Android Native Bridge](#android-native-bridge)**

## API Endpoints Used

### ipapi.is (Keyless)
- **URL:** `https://ipapi.co/{IP}/json/`
- **Free Tier:** 1000 requests/day
- **Features:** IP geolocation, ASN info, timezone
- **No API key required** for basic usage

### OpenStreetMap Nominatim (Keyless)
- **URL:** `https://nominatim.openstreetmap.org/reverse?format=json&lat={LAT}&lon={LON}`
- **Free Tier:** 1 request/second, 1000 requests/day
- **Features:** Reverse geocoding, address lookup
- **No API key required** for basic usage

## Production Considerations

### Rate Limiting
Add rate limiting middleware to prevent abuse:
```javascript
const rateLimit = require('express-rate-limit');
// Implement in your MCP client or add a proxy layer
```

### API Keys (Optional)
For higher rate limits, use paid tiers:
- **ipinfo.io:** $0.50/1000 requests (better accuracy than ipapi.is)
- **Nominatim Premium:** Contact OpenStreetMap Foundation

### Privacy & Compliance
- ⚠️ **IP detection reveals exit nodes** when users are on VPN/Tor
- ⚠️ **Cache results** to reduce API calls and respect rate limits
- ⚠️ **Don't log coordinates** - only store what's necessary
- ✅ **Inform users** that location data may be approximate

## Android Native Bridge (For Real GPS)

To get actual GPS coordinates from the device, you need a native bridge:

### Architecture
```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Android   │────▶│  Location    │────▶│   MCP Server│
│   App       │     │  Service     │     │   (Node.js) │
└─────────────┘     └──────────────┘     └─────────────┘
```

### Step 1: Android Location Service
Create a local HTTP server in your Android app that exposes GPS data:

```kotlin
// LocationService.kt
class LocationService : Service() {
    private val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    fun getLocation(): Location? {
        return locationManager.lastKnownLocation(LocationManager.GPS_PROVIDER)
    }
    
    // Expose via local HTTP server on port 9000
}
```

### Step 2: Update MCP Tool
Modify `get_current_location` in `server.js`:
```javascript
server.tool(
  'get_current_location',
  'Get current GPS coordinates from Android device',
  {},
  async () => {
    const res = await executeHttpRequest('http://localhost:9000/location');
    return { content: [{ type: 'text', text: JSON.stringify(res.body, null, 2) }] };
  }
);
```

### Step 3: Update Manifest
Add to `mcp/tools/manifest.json`:
```json
{
  "name": "get_current_location",
  "category": "LOCATION",
  "description": "Get real-time GPS coordinates from Android device (requires native bridge)",
  "parameters": []
}
```

## Testing

### Test IP Geolocation
```bash
# From another terminal, test the server
curl http://localhost:3000/tools/list  # MCP discovery
```

### Test Reverse Geocoding
```javascript
// In your MCP client or via execute_code tool
await callTool('reverse_geocode', { 
  latitude: 40.7128, 
  longitude: -74.0060,
  format: 'address' 
});
// Expected: "New York, New York 10007, United States of America"
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `ipapi.co` returns 404 | Check IP format (no spaces, valid IPv4/IPv6) |
| Nominatim rate limited | Add delay between calls or use API key |
| GPS tool returns placeholder | Implement Android native bridge (see above) |
| Connection timeout | Increase timeout in `executeHttpRequest` function |

## License
Same as GPT Mobile AI project.
