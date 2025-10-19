package sk.martinvanco.blarp.core.presentation.error_string_mapper

import sk.martinvanco.blarp.core.domain.Error

fun Error.toErrorString(): String {
    return when (this) {
        is Error.NetworkError -> "Network error: $message"
        is Error.ApiError -> "API error ($code): $message"
        is Error.DatabaseError -> "Database error: $message"
        is Error.UnknownError -> "Unknown error: $message"
    }
}
