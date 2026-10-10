package app.rev.domain.model

/** Minimal typed result for operations whose failures the UI must distinguish. */
sealed interface Result<out T, out E> {
    data class Ok<T>(val value: T) : Result<T, Nothing>
    data class Err<E>(val error: E) : Result<Nothing, E>
}
