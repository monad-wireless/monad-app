package sk.martinvanco.monad.core.di

import org.koin.dsl.module
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.core.data.database.SqlDelightDriverFactory
import sk.martinvanco.monad.core.domain.bluetooth.AndroidBluetoothStateChecker
import sk.martinvanco.monad.core.domain.bluetooth.BluetoothStateChecker
import sk.martinvanco.monad.core.domain.toast.AndroidToastManager
import sk.martinvanco.monad.core.domain.toast.ToastManager
import sk.martinvanco.monad.core.util.ContextProvider

actual val platformModule = module {
    // Database
    single {
        SqlDelightDriverFactory(ContextProvider.getContext())
    }

    single {
        Database(get<SqlDelightDriverFactory>().createDriver())
    }

    single<BluetoothStateChecker> {
        AndroidBluetoothStateChecker(ContextProvider.getContext())
    }

    single<ToastManager> {
        AndroidToastManager(ContextProvider.getContext())
    }
}
