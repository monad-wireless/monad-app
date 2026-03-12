package sk.martinvanco.monad.core.domain

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import sk.martinvanco.monad.core.config.isDebug
import sk.martinvanco.monad.core.data.remote.KtorClient

class NetworkHandler(private val ktorClient: KtorClient) {

    fun <T> invokeApi(
        call: suspend KtorClient.() -> T
    ): Flow<ResultHandler<T, DataError.NetworkError>> = flow {

        try {
            // 1. Emit loading state
            emit(ResultHandler.Loading())

            if (isDebug()) println("🔵 NetworkHandler: Executing API call...")

            // 2. Execute the API call
            val result = ktorClient.call()

            if (isDebug()) println("🟢 NetworkHandler: API call successful")

            // 3. Emit success with data
            emit(ResultHandler.Success(result))

        } catch (e: ClientRequestException) {
            // 4xx errors (400, 401, 404, etc.)
            val errorBody = try {
                e.response.bodyAsText()
            } catch (ex: Exception) {
                "Unable to read error body"
            }

            if (isDebug()) {
                println("🔴 NetworkHandler: Client error ${e.response.status.value}")
                println("🔴 NetworkHandler: Error body: $errorBody")
            }

            val networkError = when (e.response.status) {
                HttpStatusCode.BadRequest ->
                    DataError.NetworkError.BAD_REQUEST
                HttpStatusCode.Unauthorized ->
                    DataError.NetworkError.UNAUTHORISED
                HttpStatusCode.Forbidden ->
                    DataError.NetworkError.FORBIDDEN
                HttpStatusCode.NotFound ->
                    DataError.NetworkError.NOT_FOUND
                HttpStatusCode.RequestTimeout ->
                    DataError.NetworkError.REQUEST_TIMEOUT
                HttpStatusCode.Conflict ->
                    DataError.NetworkError.CONFLICT
                HttpStatusCode.PayloadTooLarge ->
                    DataError.NetworkError.PAYLOAD_TOO_LARGE
                HttpStatusCode.TooManyRequests ->
                    DataError.NetworkError.TOO_MANY_REQUESTS
                else ->
                    DataError.NetworkError.CLIENT
            }
            emit(ResultHandler.Error(networkError))

        } catch (e: ServerResponseException) {
            // 5xx errors
            if (isDebug()) println("🔴 NetworkHandler: Server error ${e.response.status.value}")
            emit(ResultHandler.Error(DataError.NetworkError.SERVER))

        } catch (e: ContentConvertException) {
            // JSON parsing errors
            if (isDebug()) {
                println("🔴 NetworkHandler: Serialization error - ${e.message}")
                e.printStackTrace()
            }
            emit(ResultHandler.Error(DataError.NetworkError.SERIALIZATION))

        } catch (e: Exception) {
            // Network errors or unknown errors
            val errorMessage = e.message ?: "Unknown error"
            if (isDebug()) {
                println("🔴 NetworkHandler: Exception - $errorMessage")
                e.printStackTrace()
            }

            // Check if it's a network-related error
            val networkError = if (errorMessage.contains("ConnectException", ignoreCase = true) ||
                errorMessage.contains("UnknownHost", ignoreCase = true) ||
                errorMessage.contains("timeout", ignoreCase = true) ||
                errorMessage.contains("network", ignoreCase = true)) {
                DataError.NetworkError.NO_INTERNET
            } else {
                DataError.NetworkError.UNKNOWN
            }

            emit(ResultHandler.Error(networkError))
        }
    }
}
