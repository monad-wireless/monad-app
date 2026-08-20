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
import sk.martinvanco.monad.core.domain.wifi.WifiConnectionService
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.device.data.api.DeviceService
import sk.martinvanco.monad.device.presentation.DeviceScreenModel
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.home.presentation.HomeScreenModel
import sk.martinvanco.monad.lab.data.GroundTruthRepository
import sk.martinvanco.monad.lab.data.GroundTruthTallyService
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRecovery
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.data.LabTelemetryShipper
import sk.martinvanco.monad.lab.data.LabTimeGateway
import sk.martinvanco.monad.lab.data.PreflightService
import sk.martinvanco.monad.lab.data.RoomTallyGateway
import sk.martinvanco.monad.lab.data.StorageArtefactSink
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.BeaconWitness
import sk.martinvanco.monad.lab.domain.ClockSyncService
import sk.martinvanco.monad.lab.domain.GroundTruthRecorder
import sk.martinvanco.monad.lab.domain.GroundTruthStore
import sk.martinvanco.monad.lab.domain.IdentityBroadcaster
import sk.martinvanco.monad.lab.domain.LabDatagramSocket
import sk.martinvanco.monad.lab.domain.LabEnvironment
import sk.martinvanco.monad.lab.domain.ForegroundWake
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.ReferenceClock
import sk.martinvanco.monad.lab.domain.PoseTracker
import sk.martinvanco.monad.lab.domain.SessionRecorder
import sk.martinvanco.monad.lab.domain.TrafficGenerator
import sk.martinvanco.monad.lab.domain.upload.ArtefactSink
import sk.martinvanco.monad.lab.presentation.GroundTruthScanScreenModel
import sk.martinvanco.monad.lab.presentation.LabConsoleScreenModel
import sk.martinvanco.monad.lab.presentation.SessionStatusScreenModel
import sk.martinvanco.monad.my_account.presentation.MyAccountScreenModel
import sk.martinvanco.monad.news.presentation.NewsScreenModel
import sk.martinvanco.monad.notifications.presentation.NotificationsScreenModel
import sk.martinvanco.monad.onboarding.presentation.OnboardingScreenModel
import sk.martinvanco.monad.quests.data.adapter.LabBundleSourceAdapter
import sk.martinvanco.monad.quests.data.adapter.LabSessionArchiveAdapter
import sk.martinvanco.monad.quests.data.adapter.ParticipantDirectoryAdapter
import sk.martinvanco.monad.quests.data.adapter.QuestCompletionGatewayAdapter
import sk.martinvanco.monad.quests.data.adapter.QuestStepJournalAdapter
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.domain.QuestSessionCoordinator
import sk.martinvanco.monad.quests.domain.port.LabBundleSource
import sk.martinvanco.monad.quests.domain.port.LabSessionArchive
import sk.martinvanco.monad.quests.domain.port.ParticipantDirectory
import sk.martinvanco.monad.quests.domain.port.QuestCompletionGateway
import sk.martinvanco.monad.quests.domain.port.QuestStepJournal
import sk.martinvanco.monad.quests.presentation.QuestsScreenModel
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreenModel
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreenModel
import sk.martinvanco.monad.storage.data.api.StorageService

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // Network + API surface
    single { WifiConnectionService() }
    single { KtorClient }
    single { AuthService(get()) }
    single { QuestsService(get()) }
    single { DeviceService(get()) }  // IP-128 — public device read behind a scanned label
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
    // The clock path for a session with no collector. Named as the port, not the gateway: the instrument
    // asks for "a clock I can measure against", and which one it gets is a deployment fact.
    single<ReferenceClock> { LabTimeGateway(get()) }
    single { TrafficGenerator(get()) }
    single { BeaconWitness(get()) }
    single { IdentityBroadcaster() }
    // Visual-inertial odometry. A singleton for the same reason the socket is: a phone has one
    // camera, and two AR sessions on one device is a refusal, not a second track.
    single { PoseTracker() }
    // Keeps the screen awake for a walk. On iOS both advertising and odometry stop when the phone
    // locks, and neither says so — see ForegroundWake.
    single { ForegroundWake() }
    single { BackgroundResidency() }
    single { LabSessionRepository(get()) }
    single { LabConfigService(get()) }
    // Ground truth (people channel). Buffered locally like every other stream and drained by the
    // same uploader — there is one path off this device, not two.
    single { GroundTruthRepository(get()) }
    // The same two objects, seen through the narrow ports the measurement path is allowed. Aliases,
    // not second instances: `get<…Repository>()` resolves the singleton above, so there is still one
    // database handle and one write path. What changes is what the instrument can reach — the
    // recorder cannot delete a row or render a TSV, and the ground-truth store cannot read another
    // participant's scans.
    single<SessionRecorder> { get<LabSessionRepository>() }
    single<GroundTruthStore> { get<GroundTruthRepository>() }
    // The room-wide tally. Read-mostly and stateless: unlike the config bundle there is nothing
    // worth caching, because the whole meaning of this number is how fresh it is.
    single<RoomTallyGateway> { GroundTruthTallyService() }
    // The upload path's rules — upload-then-delete, streams before sidecar, bounded retry — are
    // about ordering and bookkeeping, not about HTTP. Naming the network as a one-method port is
    // what lets those rules be tested against a real schema instead of believed.
    single<ArtefactSink> { StorageArtefactSink(get()) }
    single { LabSessionUploader(get(), get(), get(), get(), get()) }
    // Pre-flight readiness. Holds the same singleton socket the instrument uses, so it refuses to
    // probe while a session is live and always resets the clock service afterwards.
    single { PreflightService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Crash / kill / reboot recovery. A session left `open` is invisible to every upload path, so
    // this runs before anything else can read the backlog.
    single { LabSessionRecovery(get()) }
    single {
        LabInstrument(
            socket = get(),
            clockSync = get(),
            trafficGenerator = get(),
            beaconWitness = get(),
            broadcaster = get(),
            poseTracker = get(),
            referenceClock = get(),
            wake = get(),
            residency = get(),
            wifi = get(),
            repository = get(),
            environment = get(),
        )
    }
    single { GroundTruthRecorder(get(), get()) }
    // Live instrument health -> the lab's LGTM stack, via the API. A singleton observing the one
    // instrument, started once in `App()`: it has to outlive the console screen, because a walk
    // continues with the screen closed and that is exactly when nobody can see the handset.
    single { LabTelemetryShipper(get(), get()) }
    // The quest path's ports (see `quests/domain/port`). Adapters over the same singletons, not
    // second instances: one config service, one uploader, one user table. What changes is what the
    // coordinator can reach — it can drain the backlog but not delete a session, read step rows but
    // not write them, and read the participant but not the account.
    single<LabBundleSource> { LabBundleSourceAdapter(get()) }
    single<LabSessionArchive> { LabSessionArchiveAdapter(get(), get()) }
    single<ParticipantDirectory> { ParticipantDirectoryAdapter(get()) }
    single<QuestCompletionGateway> { QuestCompletionGatewayAdapter(get()) }
    single<QuestStepJournal> { QuestStepJournalAdapter(get()) }
    single { QuestSessionCoordinator(get(), get(), get(), get(), get(), get()) }

    // Screen models
    factory { SplashScreenModel(get(), get(), get()) }
    factory { OnboardingScreenModel(get(), get()) }
    factory { LoginScreenModel(get(), get(), get()) }
    factory { RegisterScreenModel(get(), get(), get()) }
    factory { HomeScreenModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { QuestsScreenModel() }
    factory { (questId: String) -> QuestDetailScreenModel(get(), get(), get(), questId) }
    // IP-128 — device landing. questId is the optional `?q=` from the deep link.
    factory { (slug: String, questId: String?) -> DeviceScreenModel(get(), get(), slug, questId) }
    factory { (questId: String) -> ActiveQuestScreenModel(get(), get(), get(), get(), get(), questId) }
    factory { NewsScreenModel() }
    factory { NotificationsScreenModel() }
    factory { MyAccountScreenModel(get(), get()) }
    factory { LabConsoleScreenModel(get(), get(), get(), get(), get(), get(), get()) }
    factory { GroundTruthScanScreenModel(get(), get(), get(), get()) }
    // "Am I recording?" — the participant surface for a backgrounded session.
    factory { SessionStatusScreenModel(get(), get(), get(), get(), get(), get()) }
}
