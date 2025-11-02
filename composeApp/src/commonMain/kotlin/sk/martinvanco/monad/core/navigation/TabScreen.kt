package sk.martinvanco.monad.core.navigation

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.menu_home
import monad.composeapp.generated.resources.menu_news
import monad.composeapp.generated.resources.menu_notifications
import monad.composeapp.generated.resources.menu_quests
import org.jetbrains.compose.resources.DrawableResource
import sk.martinvanco.monad.home.presentation.HomeScreen
import sk.martinvanco.monad.news.presentation.NewsScreen
import sk.martinvanco.monad.notifications.presentation.NotificationsScreen
import sk.martinvanco.monad.quests.presentation.QuestsScreen

/**
 * Sealed class representing bottom navigation tabs
 */
sealed class TabScreen : Tab, java.io.Serializable {

    abstract val label: String
    abstract val icon: DrawableResource

    override val key: String get() = label

    data object HomeTab : TabScreen() {
        override val label: String = "Home"
        @Transient
        override val icon: DrawableResource = Res.drawable.menu_home

        override val options: TabOptions
            @Composable
            get() = TabOptions(
                index = 0u,
                title = label
            )

        @Composable
        override fun Content() {
            HomeScreen().Content()
        }
    }

    data object QuestsTab : TabScreen() {
        override val label: String = "Quests"
        @Transient
        override val icon: DrawableResource = Res.drawable.menu_quests

        override val options: TabOptions
            @Composable
            get() = TabOptions(
                index = 1u,
                title = label
            )

        @Composable
        override fun Content() {
            QuestsScreen().Content()
        }
    }

    data object NewsTab : TabScreen() {
        override val label: String = "News"
        @Transient
        override val icon: DrawableResource = Res.drawable.menu_news

        override val options: TabOptions
            @Composable
            get() = TabOptions(
                index = 2u,
                title = label
            )

        @Composable
        override fun Content() {
            NewsScreen().Content()
        }
    }

    data object NotificationsTab : TabScreen() {
        override val label: String = "Notifications"
        @Transient
        override val icon: DrawableResource = Res.drawable.menu_notifications

        override val options: TabOptions
            @Composable
            get() = TabOptions(
                index = 3u,
                title = label
            )

        @Composable
        override fun Content() {
            NotificationsScreen().Content()
        }
    }

    companion object {
        val tabs = listOf(HomeTab, QuestsTab, NewsTab, NotificationsTab)
    }
}
