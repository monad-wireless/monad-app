package dev.dubec.monad.core.di

import org.koin.dsl.module
import dev.dubec.monad.Database
import dev.dubec.monad.core.data.database.SqlDelightDriverFactory
import dev.dubec.monad.core.domain.bluetooth.BluetoothStateChecker
import dev.dubec.monad.core.domain.bluetooth.IosBluetoothStateChecker
import dev.dubec.monad.core.domain.toast.IosToastManager
import dev.dubec.monad.core.domain.toast.ToastManager

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

    single<ToastManager> {
        IosToastManager()
    }
}
