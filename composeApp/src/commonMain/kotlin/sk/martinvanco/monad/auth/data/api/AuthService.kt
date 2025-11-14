package sk.martinvanco.monad.auth.data.api

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import sk.martinvanco.monad.auth.data.dto.LoginRequestDto
import sk.martinvanco.monad.auth.data.dto.LoginResponseDto
import sk.martinvanco.monad.core.data.remote.KtorClient

class AuthService(private val ktorClient: KtorClient) {

    suspend fun login(email: String, password: String): LoginResponseDto {
        val request = LoginRequestDto(email = email, password = password)

        val response = ktorClient.client.post("/api/auth/login") {
            setBody(request)
        }

        return response.body<LoginResponseDto>()
    }
}
