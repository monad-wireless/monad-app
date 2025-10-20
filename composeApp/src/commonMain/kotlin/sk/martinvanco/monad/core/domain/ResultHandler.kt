package sk.martinvanco.monad.core.domain

sealed class ResultHandler<out T> {
    data class Success<T>(val data: T) : ResultHandler<T>()
    data class Failure(val error: Error) : ResultHandler<Nothing>()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun errorOrNull(): Error? = when (this) {
        is Success -> null
        is Failure -> error
    }

    inline fun <R> map(transform: (T) -> R): ResultHandler<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> Failure(error)
    }

    inline fun onSuccess(action: (T) -> Unit): ResultHandler<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (Error) -> Unit): ResultHandler<T> {
        if (this is Failure) action(error)
        return this
    }
}
