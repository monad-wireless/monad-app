package sk.martinvanco.monad.core.navigation

import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Navigation manager interface for handling navigation commands.
 * This allows ScreenModels to trigger navigation without directly accessing Navigator.
 */
interface NavigationManager {
    /**
     * Navigate to a new screen (push to stack)
     */
    fun navigateTo(screen: Screen)

    /**
     * Navigate back (pop from stack)
     */
    fun navigateBack()

    /**
     * Replace current screen with a new one
     */
    fun replace(screen: Screen)

    /**
     * Replace entire navigation stack with a new screen (clears back stack)
     */
    fun replaceAll(screen: Screen)
}

/**
 * Implementation of NavigationManager that emits navigation commands
 * to be collected and executed by the Navigator in App.kt
 */
class NavigationManagerImpl : NavigationManager {
    private val _navigationCommands = MutableSharedFlow<NavigationCommand>(
        extraBufferCapacity = 1 // Allow one command to be buffered
    )
    val navigationCommands: SharedFlow<NavigationCommand> = _navigationCommands.asSharedFlow()

    override fun navigateTo(screen: Screen) {
        _navigationCommands.tryEmit(NavigationCommand.Navigate(screen))
    }

    override fun navigateBack() {
        _navigationCommands.tryEmit(NavigationCommand.Back)
    }

    override fun replace(screen: Screen) {
        _navigationCommands.tryEmit(NavigationCommand.Replace(screen))
    }

    override fun replaceAll(screen: Screen) {
        _navigationCommands.tryEmit(NavigationCommand.ReplaceAll(screen))
    }
}

/**
 * Sealed interface representing navigation commands
 */
sealed interface NavigationCommand {
    data class Navigate(val screen: Screen) : NavigationCommand
    data object Back : NavigationCommand
    data class Replace(val screen: Screen) : NavigationCommand
    data class ReplaceAll(val screen: Screen) : NavigationCommand
}
