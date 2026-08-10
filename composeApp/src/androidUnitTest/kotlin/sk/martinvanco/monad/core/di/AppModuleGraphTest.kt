package sk.martinvanco.monad.core.di

import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.core.data.database.SqlDelightDriverFactory
import sk.martinvanco.monad.core.domain.bluetooth.BluetoothStateChecker
import sk.martinvanco.monad.core.domain.toast.ToastManager
import kotlin.test.Test

/**
 * The dependency graph resolves.
 *
 * Koin builds nothing until something asks for it, so a definition whose constructor gained a
 * parameter nobody provides compiles perfectly and fails at the moment the screen is opened. For
 * most of this app that is an annoyance; for `LabConsoleScreenModel` it is a failure in a room with
 * twelve participants in it and a session that cannot be re-run.
 *
 * [verify] walks every definition's constructor by reflection and asserts a provider exists for
 * each parameter type, without instantiating anything — so no database is opened and no platform
 * service is touched.
 *
 * [extraTypes] names what `platformModule` supplies. That module is an `expect`, and its Android
 * actual builds from `ContextProvider`, which has no Android framework behind it in a JVM unit
 * test. Declaring the four types it contributes keeps this test about `appModule`'s own wiring —
 * the part that changes when a constructor changes.
 */
class AppModuleGraphTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `app module graph is complete`() {
        appModule.verify(
            extraTypes = listOf(
                Database::class,
                SqlDelightDriverFactory::class,
                BluetoothStateChecker::class,
                ToastManager::class,
            )
        )
    }
}
