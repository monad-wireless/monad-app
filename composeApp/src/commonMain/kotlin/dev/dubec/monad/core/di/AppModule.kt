package dev.dubec.monad.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import dev.dubec.monad.auth.data.api.AuthService
import dev.dubec.monad.auth.data.repository.UserRepository
import dev.dubec.monad.auth.domain.AuthManager
import dev.dubec.monad.auth.domain.OperatorAccess
import dev.dubec.monad.auth.presentation.login.LoginScreenModel
import dev.dubec.monad.auth.presentation.register.RegisterScreenModel
import dev.dubec.monad.auth.presentation.splash.SplashScreenModel
import dev.dubec.monad.ble.data.BleScannerImpl
import dev.dubec.monad.ble.domain.BleScanner
import dev.dubec.monad.core.data.remote.KtorClient
import dev.dubec.monad.core.data.repository.SettingsRepository
import dev.dubec.monad.core.domain.wifi.WifiConnectionService
import dev.dubec.monad.core.navigation.NavigationManager
import dev.dubec.monad.core.navigation.NavigationManagerImpl
import dev.dubec.monad.device.data.api.DeviceService
import dev.dubec.monad.device.presentation.DeviceScreenModel
import dev.dubec.monad.marker.presentation.MarkerScreenModel
import dev.dubec.monad.facts.domain.FactDeck
import dev.dubec.monad.home.data.api.QuestsService
import dev.dubec.monad.home.presentation.HomeScreenModel
import dev.dubec.monad.lab.data.GroundTruthRepository
import dev.dubec.monad.lab.data.GroundTruthTallyService
import dev.dubec.monad.lab.data.LabConfigService
import dev.dubec.monad.lab.data.LabConfigSiteSource
import dev.dubec.monad.lab.data.QuestPlaceDirectory
import dev.dubec.monad.lab.data.LabSessionRecovery
import dev.dubec.monad.lab.data.LabSessionRepository
import dev.dubec.monad.lab.data.LabSessionUploader
import dev.dubec.monad.lab.data.LabTelemetryShipper
import dev.dubec.monad.lab.data.LabTimeGateway
import dev.dubec.monad.lab.data.PreflightService
import dev.dubec.monad.lab.data.RoomTallyGateway
import dev.dubec.monad.lab.data.StorageArtefactSink
import dev.dubec.monad.lab.domain.BackgroundResidency
import dev.dubec.monad.lab.domain.BeaconWitness
import dev.dubec.monad.lab.domain.CheckInService
import dev.dubec.monad.lab.domain.ClockSyncService
import dev.dubec.monad.lab.domain.LabSiteSource
import dev.dubec.monad.lab.domain.PlaceDirectory
import dev.dubec.monad.lab.domain.PresenceIndicator
import dev.dubec.monad.lab.domain.GroundTruthRecorder
import dev.dubec.monad.lab.domain.GroundTruthStore
import dev.dubec.monad.lab.domain.IdentityBroadcaster
import dev.dubec.monad.lab.domain.LabDatagramSocket
import dev.dubec.monad.lab.domain.HandsetIdentity
import dev.dubec.monad.lab.domain.LabEnvironment
import dev.dubec.monad.lab.domain.ForegroundWake
import dev.dubec.monad.lab.domain.LabInstrument
import dev.dubec.monad.lab.domain.ReferenceClock
import dev.dubec.monad.lab.domain.PoseTracker
import dev.dubec.monad.lab.domain.SessionRecorder
import dev.dubec.monad.lab.domain.SnapshotDigestSource
import dev.dubec.monad.lab.domain.TrafficGenerator
import dev.dubec.monad.quests.data.adapter.EnrollmentSnapshotDigestAdapter
import dev.dubec.monad.lab.domain.upload.ArtefactSink
import dev.dubec.monad.lab.presentation.CheckInScreenModel
import dev.dubec.monad.lab.presentation.LabConsoleScreenModel
import dev.dubec.monad.lab.presentation.SessionStatusScreenModel
import dev.dubec.monad.auth.domain.SessionObserver
import dev.dubec.monad.my_account.presentation.MyAccountScreenModel
import dev.dubec.monad.notifications.data.NotificationInbox
import dev.dubec.monad.notifications.data.NotificationPermissionGate
import dev.dubec.monad.notifications.data.NotificationPreferencesRepository
import dev.dubec.monad.notifications.data.NotificationRepository
import dev.dubec.monad.notifications.data.NotificationsSessionObserver
import dev.dubec.monad.notifications.data.PushCredentialsAdapter
import dev.dubec.monad.notifications.data.api.NotificationsService
import dev.dubec.monad.notifications.domain.NotificationPermission
import dev.dubec.monad.notifications.domain.PushCredentials
import dev.dubec.monad.notifications.domain.PushTokenGateway
import dev.dubec.monad.notifications.domain.PushTokenRegistrar
import dev.dubec.monad.notifications.presentation.NotificationsScreenModel
import dev.dubec.monad.notifications.presentation.settings.NotificationSettingsScreenModel
import dev.dubec.monad.profile.data.api.ProfileService
import dev.dubec.monad.profile.presentation.ProfileScreenModel
import dev.dubec.monad.onboarding.presentation.OnboardingScreenModel
import dev.dubec.monad.quests.data.adapter.LabBundleSourceAdapter
import dev.dubec.monad.quests.data.adapter.LabSessionArchiveAdapter
import dev.dubec.monad.quests.data.adapter.ParticipantDirectoryAdapter
import dev.dubec.monad.quests.data.adapter.QuestCompletionGatewayAdapter
import dev.dubec.monad.quests.data.adapter.QuestStepJournalAdapter
import dev.dubec.monad.quests.data.repository.QuestStepCompletionRepository
import dev.dubec.monad.quests.domain.QuestSessionCoordinator
import dev.dubec.monad.quests.domain.port.LabBundleSource
import dev.dubec.monad.quests.domain.port.LabSessionArchive
import dev.dubec.monad.quests.domain.port.ParticipantDirectory
import dev.dubec.monad.quests.domain.port.QuestCompletionGateway
import dev.dubec.monad.quests.domain.port.QuestStepJournal
import dev.dubec.monad.quests.presentation.active_quest.ActiveQuestScreenModel
import dev.dubec.monad.quests.presentation.quest_completed.QuestCompletedScreenModel
import dev.dubec.monad.quests.presentation.quest_detail.QuestDetailScreenModel
import dev.dubec.monad.storage.data.api.StorageService

val appModule = module {
    // Navigation
    single<NavigationManager> { NavigationManagerImpl() }

    // Network + API surface
    single { WifiConnectionService() }
    single { KtorClient }
    single { AuthService(get()) }
    single { QuestsService(get()) }
    single { ProfileService(get()) }
    single { DeviceService(get()) }  // IP-128 — public device read behind a scanned label
    single { StorageService(get()) }
    single { NotificationsService(get()) }  // IP-157 — inbox, push token, preferences

    // Repositories
    single { UserRepository(get()) }
    single { QuestStepCompletionRepository(get()) }
    single { SettingsRepository(get()) }
    // IP-149 — the installation's identity and its descriptor. Over the settings store, because
    // that is the one thing that lives exactly as long as the installation does.
    single { HandsetIdentity(get()) }

    // IP-157 — notifications. One inbox for the process, because two surfaces read it (the list
    // and the badge on the top bar) and they must agree. The push token's lifecycle is tied to the
    // account through `SessionObserver`, so `AuthManager` names a port and not this package.
    single { NotificationRepository(get()) }
    single { NotificationInbox(get(), get(), get()) }
    single { NotificationPreferencesRepository(get(), get(), get()) }
    single { NotificationPermission() }
    single<PushTokenGateway> { get<NotificationsService>() }
    single<PushCredentials> { PushCredentialsAdapter(get(), get(), get()) }
    single { PushTokenRegistrar(get(), get()) }
    single { NotificationPermissionGate(get(), get(), get()) }
    single<SessionObserver> { NotificationsSessionObserver(get(), get(), get()) }

    // Domain
    // One operator flag for the process. Several surfaces branch on it — the home screen, the top
    // bar, the account screen — and two copies that disagree would show a console next to a card
    // saying there is none.
    single { OperatorAccess(get()) }
    single { AuthManager(get(), get(), get(), get()) }

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
    // about ordering and bookkeeping, not about HTTP. Naming the network as a port is what lets
    // those rules be tested against a real schema instead of believed. It carries two protocols
    // because there are two: one body for a TSV, and parts for a mesh — see `PartedUpload`.
    single<ArtefactSink> { StorageArtefactSink(get()) }
    // The IP-162 evidence manifest names the frozen step snapshot's digest; the rows live in the
    // quest journal, reached through a lab-side port so the upload path names no quest repository.
    single<SnapshotDigestSource> { EnrollmentSnapshotDigestAdapter(get()) }
    single { LabSessionUploader(get(), get(), get(), get(), get(), telemetry = get(), snapshots = get()) }
    // Pre-flight readiness. Holds the same singleton socket the instrument uses, so it refuses to
    // probe while a session is live and always resets the clock service afterwards.
    single { PreflightService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Crash / kill / reboot recovery. A session left `open` is invisible to every upload path, so
    // this runs before anything else can read the backlog.
    single { LabSessionRecovery(get(), telemetry = get()) }
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

    // Check-in: scan any card, and the phone counts the visit. One for the process, on a scope
    // that outlives every screen — a check-in runs for hours with the app backgrounded, the OS
    // indicator's Check out arrives with no screen open, and the ceiling has to fire whether or
    // not anybody is looking. A screen-scoped instance would lose all three.
    single<LabSiteSource> { LabConfigSiteSource(get()) }
    single<PlaceDirectory> { QuestPlaceDirectory(get(), get()) }
    single { PresenceIndicator() }
    // The process scope. Declared rather than built inline so `AppModuleGraphTest` can walk the
    // graph, and `SupervisorJob` so one failed child — a refused broadcast, an indicator the OS
    // would not draw — cannot cancel the coroutine that is counting somebody's visit.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single {
        CheckInService(
            recorder = get(),
            places = get(),
            broadcaster = get(),
            indicator = get(),
            site = get(),
            scope = get(),
        )
    }
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
    single { QuestSessionCoordinator(get(), get(), get(), get(), get(), get(), get()) }

    // IP-146 — the Foundation-track reading matter shown during a probe dwell. A singleton
    // because the bundle is parsed once and held: a dwell is thirty seconds of somebody standing
    // still, and it must not spend any of them decoding 110 KB of JSON.
    single { FactDeck() }

    // Screen models
    factory { SplashScreenModel(get(), get(), get(), get(), get()) }
    factory { OnboardingScreenModel(get(), get()) }
    factory { LoginScreenModel(get(), get(), get()) }
    factory { RegisterScreenModel(get(), get(), get()) }
    factory { HomeScreenModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { (questId: String) -> QuestDetailScreenModel(get(), get(), get(), get(), questId) }
    // IP-128 — device landing. questId is the optional `?q=` from the deep link.
    factory { (slug: String, questId: String?) -> DeviceScreenModel(get(), get(), slug, questId) }
    // IP-140 — resolves a scanned marker card to a runnable quest and starts it.
    factory { (code: String, scanned: String) -> MarkerScreenModel(get(), get(), get(), get(), code, scanned) }
    factory { (questId: String) -> ActiveQuestScreenModel(get(), get(), get(), get(), get(), questId) }
    // IP-157 — the permission moment: the pre-prompt card after the first completed quest.
    factory { QuestCompletedScreenModel(get(), get()) }
    factory { NotificationsScreenModel(get()) }
    factory { NotificationSettingsScreenModel(get(), get()) }
    factory { MyAccountScreenModel(get(), get()) }
    factory { ProfileScreenModel(get(), get()) }
    factory { LabConsoleScreenModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { CheckInScreenModel(get(), get(), get(), get()) }
    // "Am I recording?" — the participant surface for a backgrounded session.
    factory { SessionStatusScreenModel(get(), get(), get(), get(), get(), get()) }
}
