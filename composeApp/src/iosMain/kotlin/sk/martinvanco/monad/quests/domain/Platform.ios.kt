package sk.martinvanco.monad.quests.domain

import platform.UIKit.UIDevice

actual fun getPlatformName(): String = "iOS ${UIDevice.currentDevice.systemVersion}"
