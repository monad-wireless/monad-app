package sk.martinvanco.monad.quests.domain

import android.os.Build
import java.util.TimeZone

actual fun getPlatformName(): String = "Android ${Build.VERSION.SDK_INT}"

actual fun getDeviceTimezone(): String = TimeZone.getDefault().id
