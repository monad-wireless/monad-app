package dev.dubec.monad.core.di

import org.koin.dsl.module
import dev.dubec.monad.Database
import dev.dubec.monad.core.data.database.SqlDelightDriverFactory
import dev.dubec.monad.core.domain.bluetooth.AndroidBluetoothStateChecker
import dev.dubec.monad.core.domain.bluetooth.BluetoothStateChecker
import dev.dubec.monad.core.domain.toast.AndroidToastManager
import dev.dubec.monad.core.domain.toast.ToastManager
import dev.dubec.monad.core.util.ContextProvider

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
