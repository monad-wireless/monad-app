package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.Serializable
import sk.martinvanco.monad.lab.domain.HandsetDescriptor

/**
 * Body of `POST /api/quest/{id}/start` (IP-149).
 *
 * One key, `handset`, matching the backend's `HandsetDescriptor::fromRequestBody()`. The backend
 * validates a closed set of keys inside it and freezes the whole object on the enrollment.
 */
@Serializable
data class QuestStartRequestDto(
    val handset: HandsetDescriptor,
)
