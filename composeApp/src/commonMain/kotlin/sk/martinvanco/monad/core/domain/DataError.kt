package sk.martinvanco.monad.core.domain

sealed interface DataError {
    enum class NetworkError : DataError {
        // 3xx
        RESPONSE,

        // 4xx
        BAD_REQUEST,           // 400
        UNAUTHORISED,          // 401
        FORBIDDEN,             // 403
        NOT_FOUND,             // 404
        REQUEST_TIMEOUT,       // 408
        CONFLICT,              // 409
        PAYLOAD_TOO_LARGE,     // 413
        TOO_MANY_REQUESTS,     // 429
        CLIENT,                // Other 4xx

        // 5xx
        SERVER,                // 500, 502, 503, etc.

        // Others
        SERIALIZATION,         // JSON parse failed
        NO_INTERNET,           // Network unavailable
        UNKNOWN                // Unexpected error
    }
}
