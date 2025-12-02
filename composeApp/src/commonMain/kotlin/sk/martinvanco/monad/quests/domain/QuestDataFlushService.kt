package sk.martinvanco.monad.quests.domain

import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.ble.data.repository.BleAdvertisementRepository
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository

/**
 * Service for flushing quest-related data from local storage
 * after quest completion, failure, or abandonment.
 */
class QuestDataFlushService(
    private val questStepCompletionRepository: QuestStepCompletionRepository,
    private val bleAdvertisementRepository: BleAdvertisementRepository,
    private val userRepository: UserRepository
) {
    /**
     * Flush all data for a specific quest enrollment.
     * This should be called after quest completion or abandonment.
     *
     * @param enrollmentId The quest enrollment ID
     * @param questId The quest ID (used for BLE data cleanup)
     * @param userId The user ID to clear active quest reference
     */
    suspend fun flushQuestData(enrollmentId: String, questId: String, userId: Long) {
        // Delete step completion records for this enrollment
        questStepCompletionRepository.deleteByEnrollmentId(enrollmentId)

        // Delete BLE advertisement records for this quest
        bleAdvertisementRepository.deleteByQuestId(questId)

        // Clear the active quest reference in user table
        userRepository.clearActiveQuestId(userId)
    }

    /**
     * Flush all quest-related data from local storage.
     * This is a complete reset - use with caution.
     */
    suspend fun flushAllQuestData() {
        // Delete all step completion records
        questStepCompletionRepository.deleteAll()

        // Delete all BLE advertisement records
        bleAdvertisementRepository.deleteAll()

        // Clear active quest for current user
        userRepository.getCurrentUser()?.let { user ->
            userRepository.clearActiveQuestId(user.id)
        }
    }

    /**
     * Check if there is any quest data in local storage.
     * Useful for detecting interrupted quests on app restart.
     */
    suspend fun hasActiveQuestData(): Boolean {
        val activeQuestId = userRepository.getCurrentUserActiveQuestId()
        return activeQuestId != null
    }

    /**
     * Get the active quest ID if there is one.
     */
    suspend fun getActiveQuestId(): String? {
        return userRepository.getCurrentUserActiveQuestId()
    }
}
