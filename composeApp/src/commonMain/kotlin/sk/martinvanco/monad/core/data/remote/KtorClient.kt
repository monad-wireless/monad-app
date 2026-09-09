package sk.martinvanco.monad.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.config.isDebug

object KtorClient {

    val client: HttpClient by lazy {
        HttpClient {
            expectSuccess = true

            defaultRequest {
                url {
                    takeFrom(AppConfig.BASE_URL)
                }
                contentType(
                    type = ContentType.Application.Json
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = AppConfig.REQUEST_TIMEOUT
                // Both stated, because an unset socket timeout is an engine default rather than no
                // limit — OkHttp reads for ten seconds and then gives up. Leaving it unset made
                // every per-call `timeout { requestTimeoutMillis = … }` in StorageService a
                // half-measure: the generous clock governed the call and a hidden 10 s clock
                // governed the silence inside it. See AppConfig.UPLOAD_SOCKET_TIMEOUT.
                socketTimeoutMillis = AppConfig.SOCKET_TIMEOUT
                connectTimeoutMillis = AppConfig.SOCKET_TIMEOUT
            }

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        if (isDebug()) println("HTTP Client: $message")
                    }
                }
                level = if (isDebug()) LogLevel.ALL else LogLevel.NONE
            }
        }
    }
}
