package sk.martinvanco.monad.quests.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.QuestStepCompletion

class QuestStepCompletionRepository(
    private val database: Database
) {
    private val queries = database.questStepCompletionQueries

    suspend fun insertStepCompletion(
        backendId: String,
        enrollmentId: String,
        questStepId: String,
        stepOrder: Int,
        stepType: String,
        stepName: String,
        stepConfig: String?,
        status: String = "pending",
        createdAt: Long
    ) = withContext(Dispatchers.IO) {
        queries.insertStepCompletion(
            backendId = backendId,
            enrollmentId = enrollmentId,
            questStepId = questStepId,
            stepOrder = stepOrder.toLong(),
            stepType = stepType,
            stepName = stepName,
            stepConfig = stepConfig,
            status = status,
            createdAt = createdAt
        )
    }

    suspend fun updateStepStatus(
        backendId: String,
        status: String,
        startedAt: Long?
    ) = withContext(Dispatchers.IO) {
        queries.updateStepStatus(
            status = status,
            startedAt = startedAt,
            backendId = backendId
        )
    }

    suspend fun markStepCompleted(
        backendId: String,
        completedAt: Long
    ) = withContext(Dispatchers.IO) {
        queries.markStepCompleted(
            completedAt = completedAt,
            backendId = backendId
        )
    }

    suspend fun markStepFailed(
        backendId: String,
        completedAt: Long,
        skipMessage: String?,
        skipErrorCode: String?
    ) = withContext(Dispatchers.IO) {
        queries.markStepFailed(
            completedAt = completedAt,
            skipMessage = skipMessage,
            skipErrorCode = skipErrorCode,
            backendId = backendId
        )
    }

    suspend fun updateStepData(
        backendId: String,
        stepData: String
    ) = withContext(Dispatchers.IO) {
        queries.updateStepData(
            stepData = stepData,
            backendId = backendId
        )
    }

    suspend fun getByEnrollmentId(enrollmentId: String): List<QuestStepCompletion> = withContext(Dispatchers.IO) {
        queries.selectByEnrollmentId(enrollmentId).executeAsList()
    }

    suspend fun getByBackendId(backendId: String): QuestStepCompletion? = withContext(Dispatchers.IO) {
        queries.selectByBackendId(backendId).executeAsOneOrNull()
    }

    suspend fun getActiveStep(enrollmentId: String): QuestStepCompletion? = withContext(Dispatchers.IO) {
        queries.selectActiveStep(enrollmentId).executeAsOneOrNull()
    }

    suspend fun getCompletedSteps(enrollmentId: String): List<QuestStepCompletion> = withContext(Dispatchers.IO) {
        queries.selectCompletedSteps(enrollmentId).executeAsList()
    }

    suspend fun getPendingSteps(enrollmentId: String): List<QuestStepCompletion> = withContext(Dispatchers.IO) {
        queries.selectPendingSteps(enrollmentId).executeAsList()
    }

    suspend fun countByEnrollmentId(enrollmentId: String): Long = withContext(Dispatchers.IO) {
        queries.countByEnrollmentId(enrollmentId).executeAsOne()
    }

    suspend fun countCompletedByEnrollmentId(enrollmentId: String): Long = withContext(Dispatchers.IO) {
        queries.countCompletedByEnrollmentId(enrollmentId).executeAsOne()
    }

    suspend fun deleteByEnrollmentId(enrollmentId: String) = withContext(Dispatchers.IO) {
        queries.deleteByEnrollmentId(enrollmentId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAll()
    }
}
