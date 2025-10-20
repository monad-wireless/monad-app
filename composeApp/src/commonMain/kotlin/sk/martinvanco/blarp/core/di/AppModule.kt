package sk.martinvanco.blarp.core.di

import org.koin.dsl.module
import sk.martinvanco.blarp.auth.presentation.login.LoginScreenModel
import sk.martinvanco.blarp.auth.presentation.register.RegisterScreenModel
import sk.martinvanco.blarp.auth.presentation.splash.SplashScreenModel
import sk.martinvanco.blarp.detail_screen.presentation.DetailScreenModel
import sk.martinvanco.blarp.home.presentation.HomeScreenModel
import sk.martinvanco.blarp.home_screen.presentation.HomeScreenModel as OldHomeScreenModel
import sk.martinvanco.blarp.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.blarp.news.presentation.NewsScreenModel
import sk.martinvanco.blarp.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.blarp.quests.presentation.QuestsScreenModel

val appModule = module {
    // Old screen models (can be removed later)
    factory { OldHomeScreenModel() }
    factory { (itemName: String) -> DetailScreenModel(itemName) }

    // New screen models
    factory { SplashScreenModel() }
    factory { LoginScreenModel() }
    factory { RegisterScreenModel() }
    factory { HomeScreenModel() }
    factory { QuestsScreenModel() }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel() }
}
