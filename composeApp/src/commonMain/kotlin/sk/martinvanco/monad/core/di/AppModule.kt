package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.login.LoginScreenModel
import sk.martinvanco.monad.auth.presentation.register.RegisterScreenModel
import sk.martinvanco.monad.auth.presentation.splash.SplashScreenModel
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.core.domain.NetworkHandler
import sk.martinvanco.monad.ble.data.BleScannerImpl
import sk.martinvanco.monad.ble.data.repository.BleAdvertisementRepository
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.ble.domain.BleSensingService
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.home.presentation.HomeScreenModel
import sk.martinvanco.monad.storage.data.api.StorageService
import sk.martinvanco.monad.storage.domain.BleDataExportService
import sk.martinvanco.monad.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.monad.news.presentation.NewsScreenModel
import sk.martinvanco.monad.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.QuestDataExportService
import sk.martinvanco.monad.quests.domain.QuestDataFlushService
import sk.martinvanco.monad.quests.presentation.QuestsScreenModel
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreenModel
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreenModel
import sk.martinvanco.monad.wifi_test_v2.presentation.WifiTestV2ScreenModel
import sk.martinvanco.monad.core.domain.wifi_v2.WifiConnectionServiceV2

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // Services
    single { WifiConnectionServiceV2() }
    single { KtorClient }
    single { KtorClient.client }
    single { NetworkHandler(get()) }
    single { AuthService(get()) }
    single { QuestsService(get()) }
    single { StorageService(get()) }

    // Repositories
    single { UserRepository(get()) }
    single { BleAdvertisementRepository(get()) }
    single { QuestStepCompletionRepository(get()) }

    // Domain
    single { AuthManager(get(), get()) }
    single { BleDataExportService(get(), get(), get()) }
    single { QuestDataExportService(get()) }
    single { QuestDataFlushService(get(), get(), get()) }

    // BLE - now with dependencies
    single<BleScanner> { BleScannerImpl(get(), get()) }
    single { BleSensingService(get(), get()) }

    // New screen models
    factory { SplashScreenModel(get(), get()) }
    factory { LoginScreenModel(get(), get(), get()) }
    factory { RegisterScreenModel(get(), get(), get()) }
    factory { HomeScreenModel(get(), get(), get(), get(), get(), get()) }
    factory { QuestsScreenModel() }
    factory { (questId: String) -> QuestDetailScreenModel(get(), get(), get(), questId) }
    factory { (questId: String) -> ActiveQuestScreenModel(get(), get(), get(), get(), get(), get(), get(), questId) }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel(get(), get()) }
    factory { WifiTestV2ScreenModel(get()) }
}
