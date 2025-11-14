package sk.martinvanco.monad.core.domain

sealed class ResultHandler<out T, out E> {
    data class Loading<out T>(val data: T? = null) : ResultHandler<T, Nothing>()
    data class Success<out T>(val data: T) : ResultHandler<T, Nothing>()
    data class Error<out E>(val error: E) : ResultHandler<Nothing, E>()
}
