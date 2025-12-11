package sk.martinvanco.monad.home.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QuestDetailResponseDto(
    val id: String,
    val name: String,
    val description: String,
    val points: Float,
    val estimatedDuration: Int?,
    val createdAt: String,
    val steps: List<StepResponseDto>,
    val featuredImage: String? = null
)

@Serializable
data class StepResponseDto(
    val id: String,
    val name: String,
    val type: StepType,
    val order: Int,
    val config: JsonElement? = null
)

@Serializable
enum class StepType {
    @SerialName("start")
    START,

    @SerialName("wait")
    WAIT,

    @SerialName("scan_qr")
    SCAN_QR,

    @SerialName("connect_to_ap")
    CONNECT_TO_AP,

    @SerialName("walk_to")
    WALK_TO,

    @SerialName("find_ble_device")
    FIND_BLE_DEVICE,

    @SerialName("finish")
    FINISH
}
