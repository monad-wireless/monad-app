package sk.martinvanco.monad.quests.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import sk.martinvanco.monad.core.components.ScreenWithBackNavigation
import sk.martinvanco.monad.main.presentation.LocalOverlayNavigator

data class QuestDetailScreen(val questId: String) : Screen {
    @Composable
    override fun Content() {
        val overlayNavigator = LocalOverlayNavigator.current

        ScreenWithBackNavigation(
            title = "Quest Detail",
            onBackClick = { overlayNavigator?.dismiss() }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Quest ID: $questId",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
    }
}
