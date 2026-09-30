package com.example.stockdiary.common

import kotlinx.coroutines.CancellationException

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable, val message: String? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()
    
    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun <T> failure(exception: Throwable): Result<T> = Error(exception)
        fun <T> failure(message: String): Result<T> = Error(Exception(message))
    }
    
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }
    
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        else -> default
    }
    
    inline fun getOrElse(onError: (Throwable) -> @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Error -> onError(exception)
        is Loading -> onError(IllegalStateException("Result is still loading"))
    }
    
    fun exceptionOrNull(): Throwable? = when (this) {
        is Error -> exception
        else -> null
    }
    
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }
    
    inline fun <R> mapCatching(transform: (T) -> R): Result<R> {
        return try {
            map(transform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Error(e)
        }
    }
    
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Error -> this
        is Loading -> Loading
    }
    
    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (Throwable) -> Unit): Result<T> {
        if (this is Error) action(exception)
        return this
    }
    
    inline fun onError(action: (Throwable, String?) -> Unit): Result<T> {
        if (this is Error) action(exception, message)
        return this
    }
    
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (Throwable) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onFailure(exception)
        is Loading -> onFailure(IllegalStateException("Result is still loading"))
    }
}

inline fun <T> runCatching(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Error(e)
    }
}

fun <T> Result<T>.getOrThrow(): T {
    return when (this) {
        is Result.Success -> data
        is Result.Error -> throw exception
        is Result.Loading -> throw IllegalStateException("Result is still loading")
    }
}
