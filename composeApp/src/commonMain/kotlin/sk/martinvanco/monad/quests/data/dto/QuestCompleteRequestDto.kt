package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QuestCompleteRequestDto(
    @SerialName("enrollment_id") val enrollmentId: String,
    @SerialName("completed_at") val completedAt: String,
    val steps: List<StepCompletionRequestDto>,
    @SerialName("data_file") val dataFile: DataFileDto? = null
)

@Serializable
data class StepCompletionRequestDto(
    @SerialName("step_completion_id") val stepCompletionId: String,
    val status: String, // completed, failed, skipped
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String,
    @SerialName("step_data") val stepData: JsonElement? = null,
    @SerialName("skip_record") val skipRecord: SkipRecordDto? = null
)

@Serializable
data class SkipRecordDto(
    val message: String,
    @SerialName("error_code") val errorCode: String? = null,
    val metadata: JsonElement? = null
)

@Serializable
data class DataFileDto(
    val filename: String,
    val size: Long,
    val checksum: String? = null
)
