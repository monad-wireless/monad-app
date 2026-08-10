## Custom WiFi Connection Service V2

### Overview
This is a custom WiFi connection implementation created after analyzing the `kmm-wifi-connect` library source code and identifying issues with error handling.

### Key Issues Found in kmm-wifi-connect

#### iOS Implementation Problems:
1. **Line 21-26 in ConnectToWifi.ios.kt**: Uses `fetchCurrentWithCompletionHandler` immediately after applying configuration
2. **Problem**: This async call returns too quickly, before the connection is fully established
3. **Result**: Returns `false` even when connection will succeed moments later
4. **Root Cause**: iOS WiFi connections are asynchronous, and the library doesn't wait long enough

The library DOES throw exceptions for real errors (wrong password, etc.), but the timing of the verification check causes false negatives.

### Our Custom Implementation

#### Architecture
```
WifiConnectionService (expect/actual)
├── Android Implementation (API 29+)
│   ├── Uses WifiNetworkSpecifier
│   ├── Proper NetworkCallback handling
│   ├── 30-second timeout
│   └── Detailed error messages
│
└── iOS Implementation
    ├── Uses NEHotspotConfiguration
    ├── Proper NSError code parsing
    ├── 3-second verification delay
    └── Specific error detection
```

### Features

#### 1. **Better Error Handling**

**iOS Error Codes Mapped:**
- Error Code 1 → User Cancelled
- Error Code 2 → Network Not Found
- Error Code 3 → Wrong Password (Authentication Failed)
- Error Code 4 → Timeout

**Android Errors:**
- SecurityException → Permission Denied
- TimeoutCancellationException → Connection Timeout
- onUnavailable() callback → Wrong password or network issue

#### 2. **Connection Verification**

**iOS:**
```kotlin
// Apply configuration
NEHotspotConfigurationManager.sharedManager.applyConfiguration(config) { error ->
    if (error != null) {
        // Parse specific error codes
        parseError(error.code)
    } else {
        // Success - wait 3 seconds for connection to establish
        delay(3000)
        verifyConnection()
    }
}
```

**Android:**
```kotlin
// Use NetworkCallback with timeout
withTimeout(30000) {
    suspendCoroutine { continuation ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                continuation.resume(true) // Connected!
            }
            override fun onUnavailable() {
                continuation.resume(false) // Failed
            }
        }
        connectivityManager.requestNetwork(request, callback)
    }
}
```

#### 3. **Platform-Specific Error Messages**

All errors return descriptive messages:
- ✅ "Authentication failed. Please check the password."
- ✅ "Network not found. Please check the SSID."
- ✅ "Connection cancelled by user."
- ✅ "Connection timed out."
- ✅ "Permission denied. Please grant WiFi permissions."

### API

```kotlin
class WifiConnectionService {
    /**
     * Connect to WiFi network
     * Returns Result<Unit> with specific error on failure
     */
    suspend fun connect(
        ssid: String,
        password: String?,
        securityType: WifiSecurityType
    ): Result<Unit>

    /**
     * Disconnect from current network
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Get currently connected SSID
     */
    suspend fun getCurrentSsid(): String?
}
```

### Usage

```kotlin
val wifiService = WifiConnectionService()

// Connect to network
val result = wifiService.connect(
    ssid = "MyNetwork",
    password = "MyPassword123",
    securityType = WifiSecurityType.WPA2
)

result.onSuccess {
    println("Connected successfully!")
}.onFailure { error ->
    println("Connection failed: ${error.message}")
}

// Check current network
val currentSsid = wifiService.getCurrentSsid()
println("Currently connected to: $currentSsid")
```

### Comparison: Old vs New

| Feature | kmm-wifi-connect (Old) | WifiConnectionService (New) |
|---------|------------------------|-------------------------------|
| **Returns Boolean** | ✓ | ✗ (Returns Result) |
| **Wrong Password Detection (iOS)** | ✗ (False negatives) | ✓ (Detects correctly) |
| **Error Messages** | Generic | Specific and detailed |
| **Connection Verification** | Immediate (too fast) | Delayed (3s on iOS) |
| **Timeout Handling** | Limited | 30s on Android, proper handling |
| **Error Code Parsing** | Basic | Comprehensive |
| **Current SSID Check** | ✓ | ✓ |
| **Disconnect Support** | ✗ | ✓ |

### Testing

Use the WiFi Test V2 screen to test:

1. **Home Screen** → Click "WiFi Test (V2)" button
2. **Enter SSID and Password** (pre-filled with defaults)
3. **Click Connect**
4. **Observe**:
   - Detailed status messages
   - Specific error messages for wrong password
   - Current network display
   - Connection verification

### Known Limitations

#### iOS:
- **Cannot programmatically disconnect** (iOS restriction)
- **`joinOnce = false`** keeps configuration in system settings
- **Asynchronous connection API**: `NEHotspotConfigurationManager.applyConfiguration()` callback returns immediately when configuration is valid, even if password is wrong. Actual connection happens in background.
- **SSID Reading Requires Entitlement**: `NEHotspotNetwork.fetchCurrentWithCompletionHandler` returns error code [1] without `wifi-info` entitlement
- **Solution Implemented**: Added `wifi-info` entitlement + 2-second delay before SSID verification. SSID is now readable and verification works correctly.
- **Wrong Password Detection**: If SSID doesn't match after 2 seconds, connection failed (wrong password or network out of range)

#### Android:
- **Connection is app-scoped** (shows "Accessible via Monad" in WiFi settings)
- **Requires Android 10+** (API 29+)
- **WEP not supported** (deprecated by Android)
- **Network Callback Cleanup**: Fixed issue where NetworkCallback wasn't unregistered on failure, causing Android to show indefinite connection spinner even after error was returned
- **Solution Implemented**: NetworkCallback is now properly unregistered in `onUnavailable()`, timeout, and exception handlers

### Improvements Over Original

1. ✅ **Proper error detection** - Wrong password now correctly identified
2. ✅ **Better timing** - Waits for connection to establish
3. ✅ **Specific errors** - User knows exactly what went wrong
4. ✅ **Connection verification** - Actually checks if connected
5. ✅ **Timeout handling** - Won't hang forever
6. ✅ **Type-safe API** - Result type instead of Boolean
7. ✅ **Platform consistency** - Similar behavior on iOS and Android

### iOS API Behavior Analysis

**Reference Article:** [How to connect to Wi-Fi device from iOS app using Swift](https://markoengelman.com/how-to-connect-to-wi-fi-device-from-ios-app-using-swift/) by Marko Engelman

The article confirms that `NEHotspotConfigurationManager.apply()` completion block **doesn't guarantee** the device is actually connected to the network. It only confirms the configuration was applied. The article recommends using `NEHotspotNetwork.fetchCurrent` with delays/retries to verify actual connection.

#### Required Entitlements:

Add both entitlements to `iosApp.entitlements`:
```xml
<key>com.apple.developer.networking.HotspotConfiguration</key>
<true/>
<key>com.apple.developer.networking.wifi-info</key>
<true/>
```

The `wifi-info` entitlement is required for `NEHotspotNetwork.fetchCurrent` to successfully retrieve the current SSID.

#### Test Results with Detailed Logging:

**Scenario 1: Wrong Password (10 characters instead of 9)**
```
🔵 [WiFiV2] Calling applyConfiguration...
✅ [WiFiV2] applyConfiguration completed with NO ERROR  // ⚠️ No error even with wrong password!
🔵 [WiFiV2] Waiting for connection...
⚠️ [WiFiV2] fetchCurrentWithCompletionHandler returned null  // Connection never established
[] NEHotspotNetwork nehelper sent invalid result code [1]  // Error reading SSID
```

**Scenario 2: Correct Password**
```
🔵 [WiFiV2] Calling applyConfiguration...
✅ [WiFiV2] applyConfiguration completed with NO ERROR
🔵 [WiFiV2] Waiting for connection...
🔵 [WiFiV2] Current SSID: Veverka Devolo  // Connection successful!
```

**Key Findings:**
1. `applyConfiguration()` callback returns `nil` error for BOTH correct and wrong passwords
2. The callback only validates configuration format, not password correctness
3. Actual connection happens asynchronously AFTER the callback returns
4. `getCurrentSsid()` returns `null` when not connected OR when location permission missing
5. System shows "Unable to join the network" dialog but app doesn't receive this error

**Solution:** Single verification after 2-second delay (with wifi-info entitlement, SSID is available immediately)

#### Important Note about iOS Entitlements:

After adding the `wifi-info` entitlement, `NEHotspotNetwork.fetchCurrentWithCompletionHandler` should work correctly and return the current SSID. The error code `[1]` was caused by the missing entitlement.

**Before** (with only HotspotConfiguration):
```
[] NEHotspotNetwork nehelper sent invalid result code [1]
⚠️ [WiFiV2] fetchCurrentWithCompletionHandler returned null
```

**After** (with both HotspotConfiguration and wifi-info):
```
🔵 [WiFiV2] fetchCurrentWithCompletionHandler returned SSID: Veverka Devolo
✅ [WiFiV2] Connection VERIFIED!
```

### Conclusion

The custom implementation addresses WiFi connection challenges by:
1. **SSID Verification** - Checks connection status after 2-second delay with proper entitlements
2. **Error code mapping** - Properly parses iOS NSError codes when available
3. **Android NetworkCallback** - Uses proper async handling with 30-second timeout
4. **Detailed logging** - Comprehensive debug output for troubleshooting
5. **Type-safe API** - Result<Unit> with specific error messages
6. **Required Entitlements** - Both `HotspotConfiguration` and `wifi-info` for iOS

**Important Note for iOS:** The `wifi-info` entitlement is **critical**. Without it, `NEHotspotNetwork.fetchCurrent` returns error code [1] and cannot read the current SSID. With the entitlement, SSID verification works immediately after the 2-second delay.

The old library is kept for comparison, but the V2 implementation should be used for production.
