package com.taskflow.core

/** Centralized error model. Every fallible operation exposed to the UI returns [AppResult]. */
sealed interface AppError {
    val message: String
    data class Validation(override val message: String) : AppError
    data class NotFound(override val message: String) : AppError
    data class Storage(override val message: String, val cause: Throwable? = null) : AppError
    data class InvalidBackup(override val message: String) : AppError
    data class Unexpected(override val message: String, val cause: Throwable? = null) : AppError
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

class AppException(val error: AppError) : Exception(error.message)

inline fun <T> appRunCatching(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: AppException) {
    AppResult.Failure(e.error)
} catch (e: android.database.sqlite.SQLiteException) {
    AppResult.Failure(AppError.Storage("Falha ao acessar o banco de dados", e))
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(AppError.Unexpected(e.message ?: "Erro inesperado", e))
}

fun fail(error: AppError): Nothing = throw AppException(error)
