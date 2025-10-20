package sk.martinvanco.blarp.core.util

/**
 * Example showing how to use Logger in your code
 *
 * DELETE THIS FILE - it's just for reference
 */

/*
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch

// Example 1: Basic logging in ScreenModel
class ExampleScreenModel : StateScreenModel<ExampleState>(ExampleState()) {

    fun onEvent(event: ExampleEvent) {
        // Log the event
        Logger.d("Event received: $event", tag = "ExampleScreen")

        when (event) {
            is ExampleEvent.OnButtonClick -> {
                Logger.i("Button clicked by user", tag = "ExampleScreen")
                handleButtonClick()
            }
            is ExampleEvent.OnTextChange -> {
                Logger.d("Text changed to: ${event.text}")
                mutableState.value = state.value.copy(text = event.text)
            }
        }
    }

    private fun handleButtonClick() {
        screenModelScope.launch {
            try {
                Logger.d("Starting operation...", tag = "ExampleScreen")

                // Do some work
                val result = doSomething()

                Logger.d("Operation completed successfully: $result", tag = "ExampleScreen")

            } catch (e: Exception) {
                Logger.e("Operation failed", throwable = e, tag = "ExampleScreen")
                mutableState.value = state.value.copy(error = e.message)
            }
        }
    }
}

// Example 2: Logging in Composable
@Composable
fun ExampleScreen() {
    Logger.d("Screen composition started", tag = "Compose")

    val screenModel = koinScreenModel<ExampleScreenModel>()
    val state by screenModel.state.collectAsState()

    // Log when state changes
    LaunchedEffect(state.text) {
        Logger.d("UI reacted to state change: text = ${state.text}", tag = "Compose")
    }

    Button(onClick = {
        Logger.d("Button click event sent", tag = "Compose")
        screenModel.onEvent(ExampleEvent.OnButtonClick)
    }) {
        Text("Click me")
    }
}

// Example 3: Logging navigation
@Composable
fun NavigationExample() {
    val navigator = LocalNavigator.currentOrThrow

    Button(onClick = {
        Logger.i("Navigating to details screen", tag = "Navigation")
        navigator.push(DetailsScreen())
    }) {
        Text("Go to Details")
    }
}

// Example 4: Logging API calls
class ApiRepository {

    suspend fun fetchData(): Result<Data> {
        Logger.i("API call started", tag = "Network")

        return try {
            val response = api.getData()
            Logger.d("API response received: ${response.size} items", tag = "Network")
            Result.success(response)

        } catch (e: Exception) {
            Logger.e("API call failed", throwable = e, tag = "Network")
            Result.failure(e)
        }
    }
}

// Example 5: Using different log levels
fun exampleLogLevels() {
    // Verbose - very detailed
    Logger.v("Entering function with params: x=1, y=2", tag = "Debug")

    // Debug - general debugging
    Logger.d("Processing item 5 of 10", tag = "Debug")

    // Info - important information
    Logger.i("User logged in successfully", tag = "Auth")

    // Warning - something unusual but not critical
    Logger.w("Cache miss, fetching from network", tag = "Cache")

    // Error - something went wrong
    Logger.e("Failed to parse JSON", tag = "Parser")
}

// Example 6: Structured logging
fun structuredLoggingExample(user: User, action: String) {
    Logger.i(
        """
        User Action:
        - User ID: ${user.id}
        - Action: $action
        - Timestamp: ${Clock.System.now()}
        - Session: ${getSessionId()}
        """.trimIndent(),
        tag = "Analytics"
    )
}
*/
