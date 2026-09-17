package sk.martinvanco.monad.main.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.core.navigation.CustomTopBar
import sk.martinvanco.monad.home.presentation.HomeScreen
import sk.martinvanco.monad.my_account.presentation.MyAccountScreen
import sk.martinvanco.monad.notifications.data.NotificationInbox
import sk.martinvanco.monad.notifications.presentation.NotificationsScreen

/**
 * The home shell: the top bar over [HomeScreen]. No bottom navigation.
 *
 * The tab scaffold (`TabScreen.tabs`) that used to sit here carried three placeholder screens and
 * was bypassed by this class rendering the Home tab alone; IP-157 removed it. The inbox is reached
 * from the bell on the bar, whose badge reads the process-wide inbox so it is right on a phone with
 * no route out, and refreshes when this shell appears.
 */
class MainContainerScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val inbox = remember { getKoin().get<NotificationInbox>() }
        val unread by inbox.unreadCount.collectAsState()

        LaunchedEffect(Unit) { inbox.refresh() }

        Scaffold(
            topBar = {
                CustomTopBar(
                    onProfileIconClick = {
                        navigator.push(MyAccountScreen())
                    },
                    unreadCount = unread,
                    onNotificationsClick = {
                        navigator.push(NotificationsScreen())
                    },
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                HomeScreen().Content()
            }
        }
    }
}
