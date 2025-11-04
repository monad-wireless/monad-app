package sk.martinvanco.monad.quests.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.core.components.ScreenWithBackNavigation

data class QuestDetailScreen(val questId: String) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        ScreenWithBackNavigation(
            title = "Quest Detail",
            onBackClick = { navigator.pop() }
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
