# KMP Debugging Guide

## Quick Start - Logging

### Using the Logger Utility (Recommended)

```kotlin
import sk.martinvanco.monad.core.util.Logger

class LoginScreenModel : StateScreenModel<LoginState>(LoginState()) {

    fun onEvent(event: LoginEvent) {
        Logger.d("Login event received: $event", tag = "LoginScreen")

        when (event) {
            is LoginEvent.OnEmailChange -> {
                Logger.d("Email changed to: ${event.email}")
                mutableState.value = state.value.copy(email = event.email)
            }
            is LoginEvent.OnLoginClick -> {
                Logger.i("User attempting login", tag = "Auth")
                login()
            }
        }
    }

    private fun login() {
        try {
            // Your login logic
            Logger.d("Login successful for: ${state.value.email}")
        } catch (e: Exception) {
            Logger.e("Login failed", throwable = e, tag = "Auth")
        }
    }
}
```

### Log Levels

- **`Logger.v()`** - Verbose (detailed debug info)
- **`Logger.d()`** - Debug (general debug info)
- **`Logger.i()`** - Info (informational messages)
- **`Logger.w()`** - Warning (potential issues)
- **`Logger.e()`** - Error (errors and exceptions)

### Using println() (Simple Alternative)

```kotlin
println("Debug: Email = ${state.email}")
println("Debug: Login clicked")
```

- ✅ **Android**: Shows in Logcat
- ✅ **iOS**: Shows in Xcode console

---

## Platform-Specific Debugging

### Android Debugging

#### 1. View Logs in Logcat

In Android Studio:
- Click **Logcat** tab at bottom
- Filter by package: `dev.dubec.monad` (the `applicationId`, not the source package)
- Or filter by tag: `LoginScreen`, `Auth`, etc.

#### 2. Set Breakpoints

- Click in the gutter next to line numbers to add breakpoints
- Run app in **Debug** mode (Debug icon, not Run)
- Execution will pause at breakpoints

#### 3. Android Studio Debugger

```kotlin
fun login() {
    val email = state.value.email  // <- Set breakpoint here
    // Hover over variables to inspect
    // Step through code with F8 (step over) or F7 (step into)
}
```

---

### iOS Debugging

#### 1. View Logs in Xcode Console

1. Open `iosApp.xcodeproj` in Xcode
2. Run the app (Cmd+R)
3. Open **Console** (Cmd+Shift+Y)
4. All `Logger.*()` and `println()` will appear here

#### 2. Filter Logs in Xcode

In the console search box:
```
# Filter by tag
Auth

# Filter by level
[DEBUG]
[ERROR]
```

#### 3. Using lldb Debugger

In Xcode:
- Click in gutter to set breakpoints in Swift code
- For Kotlin code, use `Logger.d()` statements instead
- Note: Direct Kotlin debugging in Xcode is limited

---

## Common Debugging Scenarios

### Scenario 1: Debug State Changes

```kotlin
class LoginScreenModel : StateScreenModel<LoginState>(LoginState()) {

    fun onEvent(event: LoginEvent) {
        Logger.d("Before: ${state.value}", tag = "State")

        when (event) {
            is LoginEvent.OnEmailChange -> {
                mutableState.value = state.value.copy(email = event.email)
            }
        }

        Logger.d("After: ${state.value}", tag = "State")
    }
}
```

### Scenario 2: Debug Network Calls

```kotlin
suspend fun login(email: String, password: String) {
    Logger.i("Starting login for: $email", tag = "Network")

    try {
        val response = api.login(email, password)
        Logger.d("Response: $response", tag = "Network")

        // Process response
    } catch (e: Exception) {
        Logger.e("Network error", throwable = e, tag = "Network")
    }
}
```

### Scenario 3: Debug Navigation

```kotlin
class LoginScreen : Screen {
    @Composable
    override fun Content() {
        Logger.d("LoginScreen rendered", tag = "Navigation")

        val navigator = LocalNavigator.currentOrThrow

        Button(onClick = {
            Logger.d("Navigating to Home", tag = "Navigation")
            navigator.push(HomeScreen())
        }) {
            Text("Login")
        }
    }
}
```

### Scenario 4: Debug Composable Recomposition

```kotlin
@Composable
fun LoginScreen() {
    Logger.d("LoginScreen recomposed", tag = "Compose")

    val state by screenModel.state.collectAsState()

    LaunchedEffect(state.email) {
        Logger.d("Email changed in UI: ${state.email}", tag = "Compose")
    }
}
```

---

## Advanced Debugging

### 1. Using Napier Directly (Advanced Users)

```kotlin
import io.github.aakira.napier.Napier

Napier.d("Direct Napier usage", tag = "MyTag")
```

### 2. Conditional Logging

```kotlin
object Logger {
    private const val DEBUG = true  // Set to false for production

    fun d(message: String, tag: String? = null) {
        if (DEBUG) {
            Napier.d(message, tag = tag)
        }
    }
}
```

### 3. Structured Logging

```kotlin
Logger.d(
    """
    Login Attempt:
    - Email: ${state.email}
    - Timestamp: ${Clock.System.now()}
    - Screen: LoginScreen
    """.trimIndent(),
    tag = "Auth"
)
```

---

## Troubleshooting

### Logs not showing on iOS?

1. Make sure `Logger.init()` is called in `App.kt`
2. Check Xcode console is open (Cmd+Shift+Y)
3. Try filtering for your message text

### Logs not showing on Android?

1. Check Logcat is filtering correctly
2. Try selecting "No Filters" in Logcat dropdown
3. Make sure your device/emulator is selected

### Performance Issues?

Excessive logging can slow down your app:
```kotlin
// ❌ Bad: Logging in tight loops
for (i in 0..10000) {
    Logger.d("Item $i")
}

// ✅ Good: Log summary
Logger.d("Processed 10000 items")
```

---

## Quick Reference Card

```kotlin
// Debug info
Logger.d("Debug message")

// With tag
Logger.d("User logged in", tag = "Auth")

// With exception
Logger.e("Error occurred", throwable = exception, tag = "Network")

// Simple println (works everywhere)
println("Quick debug: $value")

// State debugging
Logger.d("State before: ${state.value}")
// ... state change
Logger.d("State after: ${state.value}")
```

---

## Best Practices

1. **Use tags** - Makes filtering easier: `Logger.d("msg", tag = "Auth")`
2. **Don't log sensitive data** - Never log passwords, tokens, etc.
3. **Remove excessive logs** - Clean up before production
4. **Use appropriate levels** - Debug for development, Error for problems
5. **Add context** - Include enough info to understand the log: `"Login failed for user: $email"`

---

## Resources

- **Napier GitHub**: https://github.com/AAkira/Napier
- **Android Logcat Docs**: https://developer.android.com/studio/debug/logcat
- **Xcode Console**: https://developer.apple.com/documentation/xcode/viewing-debug-output
