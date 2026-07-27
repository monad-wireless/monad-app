package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.login.LoginScreenModel
import sk.martinvanco.monad.auth.presentation.register.RegisterScreenModel
import sk.martinvanco.monad.auth.presentation.splash.SplashScreenModel
import sk.martinvanco.monad.ble.data.BleScannerImpl
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.core.domain.NetworkHandler
import sk.martinvanco.monad.core.domain.wifi_v2.WifiConnectionServiceV2
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.home.presentation.HomeScreenModel
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.BeaconWitness
import sk.martinvanco.monad.lab.domain.ClockSyncService
import sk.martinvanco.monad.lab.domain.LabDatagramSocket
import sk.martinvanco.monad.lab.domain.LabEnvironment
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.TrafficGenerator
import sk.martinvanco.monad.lab.presentation.LabConsoleScreenModel
import sk.martinvanco.monad.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.monad.news.presentation.NewsScreenModel
import sk.martinvanco.monad.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.monad.onboarding.presentation.OnboardingScreenModel
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.QuestSessionCoordinator
import sk.martinvanco.monad.quests.presentation.QuestsScreenModel
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreenModel
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreenModel
import sk.martinvanco.monad.storage.data.api.StorageService

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // Network + API surface
    single { WifiConnectionServiceV2() }
    single { KtorClient }
    single { KtorClient.client }
    single { NetworkHandler(get()) }
    single { AuthService(get()) }
    single { QuestsService(get()) }
    single { StorageService(get()) }

    // Repositories
    single { UserRepository(get()) }
    single { QuestStepCompletionRepository(get()) }
    single { SettingsRepository(get()) }

    // Domain
    single { AuthManager(get(), get()) }

    // BLE transport. One scanner for the whole app: two concurrent Android scans contend for the
    // same radio and halve each other's duty cycle.
    single<BleScanner> { BleScannerImpl(get()) }

    // Lab instrument (EXP-P3). Socket, clock and generator are singletons because a session is
    // singular by construction — a phone has one Wi-Fi interface and one collector.
    single { LabEnvironment() }
    single { LabDatagramSocket() }
    single { ClockSyncService(get()) }
    single { TrafficGenerator(get()) }
    single { BeaconWitness(get()) }
    single { BackgroundResidency() }
    single { LabSessionRepository(get()) }
    single { LabConfigService(get()) }
    single { LabSessionUploader(get(), get(), get()) }
    single {
        LabInstrument(
            socket = get(),
            clockSync = get(),
            trafficGenerator = get(),
            beaconWitness = get(),
            residency = get(),
            wifi = get(),
            repository = get(),
            environment = get(),
        )
    }
    single { QuestSessionCoordinator(get(), get(), get(), get(), get(), get(), get()) }

    // Screen models
    factory { SplashScreenModel(get(), get(), get()) }
    factory { OnboardingScreenModel(get(), get()) }
    factory { LoginScreenModel(get(), get(), get()) }
    factory { RegisterScreenModel(get(), get(), get()) }
    factory { HomeScreenModel(get(), get(), get(), get(), get(), get(), get()) }
    factory { QuestsScreenModel() }
    factory { (questId: String) -> QuestDetailScreenModel(get(), get(), get(), questId) }
    factory { (questId: String) -> ActiveQuestScreenModel(get(), get(), get(), get(), get(), questId) }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel(get(), get()) }
    factory { LabConsoleScreenModel(get(), get(), get(), get(), get(), get(), get()) }
}
