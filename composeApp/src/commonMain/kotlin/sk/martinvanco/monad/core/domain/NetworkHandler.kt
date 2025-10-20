package sk.martinvanco.monad.core.domain

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

suspend inline fun <reified T> safeApiCall(
    apiCall: () -> HttpResponse
): ResultHandler<T> {
    return try {
        val response = apiCall()
        if (response.status.isSuccess()) {
            ResultHandler.Success(response.body<T>())
        } else {
            ResultHandler.Failure(
                Error.ApiError(
                    code = response.status.value,
                    message = response.status.description
                )
            )
        }
    } catch (e: Exception) {
        ResultHandler.Failure(
            Error.NetworkError(
                message = e.message ?: "Unknown network error"
            )
        )
    }
}

suspend inline fun <reified T> safeDatabaseCall(
    databaseCall: () -> T
): ResultHandler<T> {
    return try {
        ResultHandler.Success(databaseCall())
    } catch (e: Exception) {
        ResultHandler.Failure(
            Error.DatabaseError(
                message = e.message ?: "Unknown database error"
            )
        )
    }
}
