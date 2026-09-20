# Location MCP Tools - Implementation Guide

## Overview

This branch adds **3 new location-based tools** to the GPT Mobile AI MCP server:

| Tool | Description | API Used | Key Features |
|------|-------------|----------|--------------|
| `geolocate_ip` | IP geolocation lookup | ipapi.is (keyless) | Auto-detect host IP, returns country/city/timezone |
| `reverse_geocode` | Lat/lon → address | OpenStreetMap Nominatim | Coordinate validation, 3 output formats |
| `get_current_location` | GPS from device | ⚠️ Placeholder | Requires Android/iOS native bridge for real GPS |

---

## Installation & Usage

### 1. Install Dependencies
```bash
cd mcp && npm install
```

### 2. Run the Server
```bash
node server.js
```

### 3. Test Tools via MCP Client or `execute_code` Tool

```javascript
// Auto-detect host IP and get geolocation
await callTool('geolocate_ip', {});

// Reverse geocode coordinates (JSON format)
await callTool('reverse_geocode', { 
  latitude: 40.7128, 
  longitude: -74.0060 
});

// Get human-readable address
await callTool('reverse_geocode', { 
  latitude: 35.6762, 
  longitude: 139.6503, 
  format: 'address' 
});
```

---

## Tool Details

### `geolocate_ip` - IP Geolocation Lookup

**Purpose**: Look up geolocation data for an IP address using keyless API.

**Parameters**:
- `ip` (optional): IP address to lookup. If omitted, auto-detects the host's public IP.

**Returns**: JSON object with:
```json
{
  "ip": "8.8.8.8",
  "city": "Mountain View",
  "region": "California",
  "country": "United States",
  "country_code": "US",
  "timezone": "America/Los_Angeles",
  "latitude": 37.4224,
  "longitude": -122.0842
}
```

**API**: ipapi.is (keyless, free tier available)
- **Rate Limit**: ~100 requests/day on free tier
- **No API key required** for basic usage

---

### `reverse_geocode` - Coordinates to Address

**Purpose**: Convert latitude/longitude coordinates to human-readable address.

**Parameters**:
- `latitude` (required): Latitude (-90 to 90)
- `longitude` (required): Longitude (-180 to 180)
- `format` (optional): Output format - `'json'`, `'address'`, or `'text'` (default: `'json'`)

**Returns**: Based on format:
- **JSON**: Full address object with display_name, address components
- **Address**: Single line human-readable address
- **Text**: Multi-line formatted output with coordinates

**API**: OpenStreetMap Nominatim (keyless)
- **Rate Limit**: ~1 request/second recommended
- **No API key required** for basic usage

**Example Output (address format)**:
```
590 Times Square, New York, NY 10036, United States
```

---

### `get_current_location` - GPS from Device

**Purpose**: Get current GPS coordinates from Android/iOS device.

**Status**: ⚠️ **Placeholder** - Requires native companion service.

**Returns**: Message explaining that GPS access requires a native bridge.

**Implementation Options**:
1. **Android Native Bridge**: Create a companion service that exposes GPS via HTTP/WebSocket
2. **IP-Based Fallback**: Use `geolocate_ip()` for approximate location
3. **Third-Party SDK**: Integrate Google Play Services Location API

---

## Production Considerations

### Rate Limiting & API Keys

| API | Free Tier | Paid Tier | Recommendation |
|-----|-----------|-----------|----------------|
| ipapi.is | 100 req/day | $5/1k req | Use free tier for testing, add key for production |
| Nominatim | 1 req/sec | N/A | Respect rate limits, cache results |

**Add API Key Support**:
```javascript
const LOCATION_API_KEY = process.env.LOCATION_MCP_API_KEY; // ipinfo.io or similar
```

### Privacy & Security

- **Cache Results**: Store last known location locally to reduce API calls
- **Don't Log Coordinates**: Never log GPS coordinates in logs
- **Document Limitations**: Users should know detected IP may be exit node (VPN/Tor)

### Offline Mode

Store last known location:
```javascript
// Save to local storage/database
fs.writeFileSync('last_location.json', JSON.stringify({
  latitude, longitude, timestamp, source: 'ip' or 'gps'
}));
```

---

## Android Native Bridge (Optional)

For **real GPS coordinates**, implement a native companion service:

### Architecture
```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Android   │────▶│  Location    │────▶│   MCP Server│
│   App       │     │  Service     │     │   (Node.js) │
└─────────────┘     └──────────────┘     └─────────────┘
```

### Example Android Service
```kotlin
// LocationService.kt
class LocationService : Service() {
    private val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    fun getLocation(): Location? {
        return locationManager.lastKnownLocation(LocationManager.GPS_PROVIDER)
    }
    
    // Expose via local HTTP server or IPC to MCP process
}
```

### Example MCP Tool Update
```javascript
server.tool(
  'get_device_gps',
  'Get GPS coordinates from Android device (requires companion service)',
  {},
  async () => {
    const res = await executeHttpRequest('http://localhost:9000/location');
    return { content: [{ type: 'text', text: JSON.stringify(res.body, null, 2) }] };
  }
);
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `geolocate_ip` returns error | Check internet connectivity; API may be rate-limited |
| `reverse_geocode` returns "not found" | Coordinates may be in ocean/polar regions; try nearby valid coordinates |
| `get_current_location` shows placeholder | This is expected - requires native bridge implementation |
| Server fails to start | Run `npm install` first; check Node.js version (18+) |

---

## Testing Commands

```bash
# Test IP geolocation (auto-detect)
curl https://ipapi.co/$(curl -s https://api.ipify.org)/json/

# Test reverse geocode
curl "https://nominatim.openstreetmap.org/reverse?format=json&lat=40.7128&lon=-74.0060"

# Run MCP server and test via client
cd mcp && node server.js
```

---

## Branch Information

- **Branch**: `feat/location-mcp-tools-v2`
- **Base**: `0.9.5.0`
- **URL**: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/feat/location-mcp-tools-v2

**Ready to test or add more features?** Reply "test" for testing instructions, "bridge" for Android native GPS code, or "merge" to prepare for PR.