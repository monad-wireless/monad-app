package sk.martinvanco.monad.auth.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import sk.martinvanco.monad.auth.data.dto.LoginRequestDto
import sk.martinvanco.monad.auth.data.dto.LoginResponseDto
import sk.martinvanco.monad.auth.data.dto.MeResponseDto
import sk.martinvanco.monad.auth.data.dto.RegisterRequestDto
import sk.martinvanco.monad.auth.data.dto.RegisterResponseDto
import sk.martinvanco.monad.core.data.remote.KtorClient

class AuthService(private val ktorClient: KtorClient) {

    suspend fun login(email: String, password: String): LoginResponseDto {
        val request = LoginRequestDto(email = email, password = password)
        val response = ktorClient.client.post("/api/auth/login") {
            setBody(request)
        }
        return response.body<LoginResponseDto>()
    }

    suspend fun register(email: String, password: String, name: String?): RegisterResponseDto {
        val request = RegisterRequestDto(email = email, password = password, name = name)
        val response = ktorClient.client.post("/api/auth/register") {
            setBody(request)
        }
        return response.body<RegisterResponseDto>()
    }

    suspend fun getMe(token: String): MeResponseDto {
        val response = ktorClient.client.get("/api/auth/me") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        return response.body<MeResponseDto>()
    }

    suspend fun deleteAccount(token: String) {
        ktorClient.client.delete("/api/auth/account") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }
}
