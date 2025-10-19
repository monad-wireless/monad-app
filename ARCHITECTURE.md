# Blarp App - Architecture Documentation

This is a Kotlin Multiplatform Mobile (KMM) app using Compose Multiplatform for shared UI between Android and iOS.

## Architecture Overview

The project follows **Clean Architecture** with three main layers:

1. **Data Layer** - Data sources, repositories, DTOs
2. **Domain Layer** - Business logic, use cases, domain models
3. **Presentation Layer** - UI components, screens, state management

## Key Technologies

- **Kotlin Multiplatform** - Shared code between Android and iOS
- **Compose Multiplatform** - Shared UI framework
- **Decompose** - Navigation and component lifecycle
- **Ktor** - HTTP client for network requests
- **SQLDelight** - Type-safe database access
- **Koin** - Dependency injection (optional, can use manual DI via RootComponent)

## Project Structure

```
composeApp/
├── src/
│   ├── commonMain/
│   │   ├── kotlin/
│   │   │   ├── App.kt                     # App entry point
│   │   │   ├── navigation/
│   │   │   │   └── RootComponent.kt       # Navigation controller
│   │   │   ├── core/
│   │   │   │   ├── data/
│   │   │   │   │   ├── database/          # SQLDelight setup
│   │   │   │   │   ├── remote/            # HTTP client
│   │   │   │   │   └── helpers/           # Utility functions
│   │   │   │   ├── domain/
│   │   │   │   │   ├── Error.kt           # Error types
│   │   │   │   │   ├── ResultHandler.kt   # API result wrapper
│   │   │   │   │   └── NetworkHandler.kt  # Network utilities
│   │   │   │   └── presentation/
│   │   │   │       ├── components/        # Reusable UI components
│   │   │   │       └── error_string_mapper/
│   │   │   └── ui/
│   │   │       └── theme/                 # App theme
│   │   ├── composeResources/              # Resources (strings, images, fonts)
│   │   └── sqldelight/                    # Database schema files
│   ├── androidMain/
│   └── iosMain/
```

## Adding a New Feature

Follow this structure for each feature:

```
[feature_name]/
├── data/
│   ├── remote/dto/              # API response DTOs
│   └── [Feature]Repository.kt   # Data repository
├── domain/
│   ├── model/                   # Domain models
│   └── use_case/                # Business logic
└── presentation/
    └── [screen_name]/
        ├── component/           # Decompose Component
        ├── [Screen].kt          # Composable UI
        └── [Screen]State.kt     # UI state
```

## Component Pattern (MVI-like)

Each screen uses Decompose components with MVI-like pattern:
- **Component**: Manages state and handles events
- **Screen**: Composable UI that subscribes to state
- **State**: Data class for UI state
- **Event**: Sealed class for user actions

## Navigation

Navigation is managed by `RootComponent` using Decompose's navigation stack.

See ARCHITECTURE.md for detailed implementation examples.
