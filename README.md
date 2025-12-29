# Monad - Wireless Indoor Tracking App

A Kotlin Multiplatform mobile application for Android and iOS that enables wireless indoor tracking through BLE device scanning and WiFi connectivity.

## Tech Stack

- **Kotlin Multiplatform** - Shared business logic between Android and iOS
- **Compose Multiplatform** - Shared UI framework
- **Voyager** - Navigation and screen management
- **Koin** - Dependency injection
- **Ktor** - HTTP client for API communication
- **SQLDelight** - Type-safe local database
- **Kable** - Bluetooth Low Energy (BLE) scanning
- **QR-Kit** - QR code scanning

## Architecture

The project follows **Clean Architecture** with three main layers:

```
composeApp/src/
├── commonMain/          # Shared code (Android + iOS)
│   ├── kotlin/
│   │   ├── auth/        # Authentication feature
│   │   ├── ble/         # BLE scanning feature
│   │   ├── core/        # Core utilities, DI, networking
│   │   ├── home/        # Home screen
│   │   ├── quests/      # Quest management
│   │   └── ui/theme/    # App theming
│   ├── composeResources/ # Shared resources (strings, images, fonts)
│   └── sqldelight/      # Database schema
├── androidMain/         # Android-specific implementations
└── iosMain/             # iOS-specific implementations
```

Each feature module follows the pattern:
- **data/** - API services, DTOs, repositories
- **domain/** - Business logic, models
- **presentation/** - Screens, state, events

## Requirements

- **Android**: API 29+ (Android 10+)
- **iOS**: iOS 14+
- **JDK**: 11+
- **Android Studio**: Ladybug or newer (with KMP plugin)
- **Xcode**: 15+ (for iOS development)

## Getting Started

### Clone the repository

```bash
git clone https://github.com/Monad-Wireless-Indoor-Tracking/monad-app.git
cd monad-app
```

### Android

1. Open the project in Android Studio
2. Sync Gradle files
3. Select an Android device/emulator (API 29+)
4. Run the `composeApp` configuration

Or via command line:
```bash
./gradlew :composeApp:assembleDebug
```

### iOS

1. Open `iosApp/iosApp.xcodeproj` in Xcode
2. Select a target device/simulator
3. Build and run (Cmd+R)

Or build the shared framework first:
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

## Configuration

### API Endpoint

The API base URL is configured in:
```
composeApp/src/commonMain/kotlin/sk/martinvanco/monad/core/config/AppConfig.kt
```

### iOS Entitlements

For WiFi and BLE features on iOS, ensure the following entitlements are configured in `iosApp/iosApp/iosApp.entitlements`:
- `com.apple.developer.networking.HotspotConfiguration`
- `com.apple.developer.networking.wifi-info`
