package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.auth.presentation.login.LoginScreenModel
import sk.martinvanco.monad.auth.presentation.register.RegisterScreenModel
import sk.martinvanco.monad.auth.presentation.splash.SplashScreenModel
import sk.martinvanco.monad.ble.data.BleScannerImpl
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.home.presentation.HomeScreenModel
import sk.martinvanco.monad.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.monad.news.presentation.NewsScreenModel
import sk.martinvanco.monad.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.monad.quests.presentation.QuestsScreenModel
import sk.martinvanco.monad.wifi_test.presentation.WifiTestScreenModel

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // BLE
    single<BleScanner> { BleScannerImpl() }

    // New screen models
    factory { SplashScreenModel(get()) }
    factory { LoginScreenModel(get()) }
    factory { RegisterScreenModel(get()) }
    factory { HomeScreenModel(get()) }
    factory { QuestsScreenModel() }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel() }
    factory { WifiTestScreenModel(get()) }
}
