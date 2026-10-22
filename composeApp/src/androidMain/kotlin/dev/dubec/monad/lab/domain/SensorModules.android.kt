package dev.dubec.monad.lab.domain

actual fun labSensorModules(): List<LabSensorModule> =
    listOf(RoomScanModuleAndroid(), UwbRangingModuleAndroid())
