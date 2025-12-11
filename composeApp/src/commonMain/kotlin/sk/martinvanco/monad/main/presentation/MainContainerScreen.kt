package sk.martinvanco.monad.main.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.core.navigation.CustomTopBar
import sk.martinvanco.monad.core.navigation.TabScreen
import sk.martinvanco.monad.my_account.presentation.MyAccountScreen

/**
 * Main container screen - DOD simplified version
 * Only shows home screen content with top bar (profile access)
 * No bottom navigation
 */
class MainContainerScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                CustomTopBar(
                    onProfileIconClick = {
                        navigator.push(MyAccountScreen())
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Only show Home tab content
                TabScreen.HomeTab.Content()
            }
        }
    }
}
