package sk.martinvanco.blarp.core.domain

sealed class Error {
    data class NetworkError(val message: String) : Error()
    data class ApiError(val code: Int, val message: String) : Error()
    data class DatabaseError(val message: String) : Error()
    data class UnknownError(val message: String) : Error()
}
