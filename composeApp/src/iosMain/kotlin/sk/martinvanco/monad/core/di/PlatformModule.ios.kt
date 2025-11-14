package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.core.data.database.SqlDelightDriverFactory
import sk.martinvanco.monad.core.domain.bluetooth.BluetoothStateChecker
import sk.martinvanco.monad.core.domain.bluetooth.IosBluetoothStateChecker
import sk.martinvanco.monad.core.domain.permissions.IosPermissionHandler
import sk.martinvanco.monad.core.domain.permissions.PermissionHandler
import sk.martinvanco.monad.core.domain.toast.IosToastManager
import sk.martinvanco.monad.core.domain.toast.ToastManager

actual val platformModule = module {
    // Database
    single {
        SqlDelightDriverFactory()
    }

    single {
        Database(get<SqlDelightDriverFactory>().createDriver())
    }

    single<BluetoothStateChecker> {
        IosBluetoothStateChecker()
    }

    single<PermissionHandler> {
        IosPermissionHandler()
    }

    single<ToastManager> {
        IosToastManager()
    }
}
