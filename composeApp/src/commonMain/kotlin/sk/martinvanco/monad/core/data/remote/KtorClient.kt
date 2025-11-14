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
                        println("HTTP Client: $message")
                    }
                }
                level = LogLevel.ALL
            }
        }
    }
}
