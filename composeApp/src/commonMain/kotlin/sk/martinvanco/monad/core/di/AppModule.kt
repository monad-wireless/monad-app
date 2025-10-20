package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.auth.presentation.login.LoginScreenModel
import sk.martinvanco.monad.auth.presentation.register.RegisterScreenModel
import sk.martinvanco.monad.auth.presentation.splash.SplashScreenModel
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.detail_screen.presentation.DetailScreenModel
import sk.martinvanco.monad.home.presentation.HomeScreenModel
import sk.martinvanco.monad.home_screen.presentation.HomeScreenModel as OldHomeScreenModel
import sk.martinvanco.monad.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.monad.news.presentation.NewsScreenModel
import sk.martinvanco.monad.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.monad.quests.presentation.QuestsScreenModel

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // Old screen models (can be removed later)
    factory { OldHomeScreenModel() }
    factory { (itemName: String) -> DetailScreenModel(itemName) }

    // New screen models
    factory { SplashScreenModel(get()) }
    factory { LoginScreenModel(get()) }
    factory { RegisterScreenModel(get()) }
    factory { HomeScreenModel() }
    factory { QuestsScreenModel() }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel() }
}
