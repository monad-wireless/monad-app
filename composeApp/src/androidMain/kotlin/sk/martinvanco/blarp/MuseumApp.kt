package sk.martinvanco.blarp

import android.app.Application
import sk.martinvanco.blarp.di.initKoin

class MuseumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
