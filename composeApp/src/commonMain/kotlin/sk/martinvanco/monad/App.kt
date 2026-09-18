package sk.martinvanco.monad

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAutofillManager
import coil3.compose.setSingletonImageLoaderFactory
import sk.martinvanco.monad.core.image.createImageLoader
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.core.di.appModule
import sk.martinvanco.monad.core.di.platformModule
import sk.martinvanco.monad.core.autofill.CredentialSave
import sk.martinvanco.monad.core.navigation.CustomScreenTransition
import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.core.deeplink.PendingDeepLink
import sk.martinvanco.monad.core.deeplink.PreSessionScreen
import sk.martinvanco.monad.core.navigation.NavigationCommand
import sk.martinvanco.monad.device.presentation.DeviceScreen
import sk.martinvanco.monad.marker.presentation.MarkerScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.core.util.Logger
import sk.martinvanco.monad.lab.data.LabTelemetryShipper
import sk.martinvanco.monad.notifications.data.NotificationInbox
import sk.martinvanco.monad.notifications.domain.PendingPushRoute
import sk.martinvanco.monad.notifications.domain.PushRoute
import sk.martinvanco.monad.notifications.domain.PushTokenRegistrar
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen
import sk.martinvanco.monad.ui.theme.AppTheme
import cafe.adriel.voyager.navigator.Navigator as VoyagerNavigator
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()
        modules(platformModule, appModule)
    }
}

@Composable
fun App() {
    // Initialize Logger, Koin and Firebase once
    remember<Unit> {
        try {
            Logger.init()
            initKoin()
        } catch (e: Exception) {
            // Already initialized
        }
        try {
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(false)
            Logger.i("Firebase initialized, Crashlytics disabled until user accepts terms")
        } catch (e: Exception) {
            Logger.e("Firebase Crashlytics error: ${e.message}", throwable = e)
        }
        // IP-157 — the push token's lifecycle and the tap-to-route path live in the registrar; a
        // push received while the app is open refreshes the inbox so the badge moves. Installed
        // here because kmpNotifier's listener list is process-wide and this block runs once.
        try {
            val inbox = getKoin().get<NotificationInbox>()
            getKoin().get<PushTokenRegistrar>().install(onPushReceived = inbox::requestRefresh)
            Logger.i("Push listener installed")
        } catch (e: Exception) {
            Logger.e("Push listener failed to install: ${e.message}", throwable = e)
        }

        // Live instrument telemetry. Started here, not in the lab console's screen model, because a
        // session outlives the screen: the participant pockets the phone and the console is gone
        // while the walk continues. `start()` is idempotent, so a recomposition costs nothing, and
        // the shipper pushes only while a session is actually recording.
        try {
            getKoin().get<LabTelemetryShipper>().start()
            Logger.i("Lab telemetry shipper started")
        } catch (e: Exception) {
            // A courier that cannot start must never stop the app it reports on.
            Logger.e("Lab telemetry shipper failed to start: ${e.message}", throwable = e)
        }
    }

    // Setup Coil ImageLoader with platform-specific networking
    setSingletonImageLoaderFactory { context ->
        createImageLoader(context)
    }

    AppTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            VoyagerNavigator(SplashScreen()) { navigator ->
                // Get NavigationManager from Koin and observe navigation commands
                val navigationManager = remember {
                    getKoin().get<NavigationManager>() as NavigationManagerImpl
                }

                LaunchedEffect(Unit) {
                    navigationManager.navigationCommands.collect { command ->
                        when (command) {
                            is NavigationCommand.Navigate -> navigator.push(command.screen)
                            is NavigationCommand.Back -> navigator.pop()
                            is NavigationCommand.Replace -> navigator.replace(command.screen)
                            is NavigationCommand.ReplaceAll -> navigator.replaceAll(command.screen)
                        }
                    }
                }

                // IP-128 — drain a deep link parked by the platform entry point.
                //
                // This is the ONLY safe place to do it. The link is captured in
                // MainActivity.onCreate / .onOpenURL, both of which run before
                // Koin is started (inside App()'s `remember`, above) and before
                // this Navigator exists — so it cannot be emitted as a
                // NavigationCommand at capture time: the SharedFlow has
                // replay = 0 and its only collector is the effect above, so the
                // command would be dropped silently on a cold start.
                //
                // GATED ON THE TOP SCREEN (2026-09-18), not on first composition.
                // It used to be `LaunchedEffect(Unit) { consume()?.let(::open) }`,
                // which routed the link immediately — and that lost every sticker
                // scanned by anybody not already signed in. The splash decides the
                // start screen asynchronously and then calls
                // `navigationManager.replace(LoginScreen())`; `replace` swaps the
                // TOP of the stack, which by then was the device screen this
                // effect had just pushed. The link was destroyed by a navigation
                // already in flight, and the participant reached the login form
                // with nothing to say a link had ever arrived.
                //
                // So the link now waits for a screen that can keep it (see
                // PreSessionScreen) and is routed when one arrives — after a
                // sign-in, after onboarding, after registration. Keyed on the
                // gate rather than on the screen instance, so an ordinary push
                // does not restart the collector; PendingDeepLink is a StateFlow,
                // so a link parked while the app is warm reaches it too.
                // consume() stays take-once, so a recomposition cannot re-fire a
                // link and yank a participant out of a running quest.
                val routableSurface = navigator.lastItem !is PreSessionScreen
                LaunchedEffect(routableSurface) {
                    if (!routableSurface) return@LaunchedEffect
                    PendingDeepLink.parked.collect { pending ->
                        if (pending == null) return@collect
                        PendingDeepLink.consume()?.let { link -> navigator.open(link) }
                    }
                }

                // The autofill session, closed so a password manager may offer to save what was
                // just accepted. Here rather than on the login screen because that screen is
                // destroyed by `replaceAll` in the same breath as the success it would react to —
                // see CredentialSave. Null on iOS, where the system needs no commit.
                val autofillManager = LocalAutofillManager.current
                LaunchedEffect(autofillManager) {
                    CredentialSave.requested.collect { requested ->
                        if (!requested) return@collect
                        if (CredentialSave.consume()) autofillManager?.commit()
                    }
                }

                // IP-157 — a tapped push. A StateFlow rather than a take-once slot, because a tap
                // can arrive while the app is already composed (see PendingPushRoute). Cleared after
                // navigating so a recomposition cannot replay it; the row is marked read when the
                // payload names it.
                LaunchedEffect(Unit) {
                    PendingPushRoute.route.collect { route ->
                        if (route == null) return@collect
                        PendingPushRoute.clear()
                        route.notificationId?.let { id ->
                            runCatching { getKoin().get<NotificationInbox>().markRead(id) }
                        }
                        when (route) {
                            is PushRoute.Quest -> navigator.push(QuestDetailScreen(route.questId))
                            is PushRoute.Link -> navigator.open(route.link)
                        }
                    }
                }

                CustomScreenTransition(navigator)
            }
        }
    }
}

/**
 * Where a printed link goes (IP-128, IP-140). One function, because the same two grammars arrive
 * from a scanned sticker (`PendingDeepLink`) and from a push payload's `deep_link` (IP-157), and
 * two `when` blocks over the same sealed type would drift.
 *
 * A marker card resolves to a run rather than to a page: the participant is standing at the card,
 * and the fastest correct thing to show them is the countdown.
 */
private fun VoyagerNavigator.open(link: DeepLink) {
    when (link) {
        is DeepLink.Device -> push(DeviceScreen(slug = link.slug, questId = link.questId))
        is DeepLink.Marker -> push(MarkerScreen(code = link.code, scannedValue = link.scannedValue))
    }
}
