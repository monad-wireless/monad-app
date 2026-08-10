package sk.martinvanco.monad.lab.domain

actual fun labSensorModules(): List<LabSensorModule> =
    listOf(RoomScanModuleAndroid(), UwbRangingModuleAndroid())
