package org.jetbrains.influxdb

expect class CommonPromise<T> {
    fun <S> then(onFulfilled: (T) -> S): CommonPromise<S>
    fun <S> then(
            onFulfilled: (T) -> S,
            onRejected: (Throwable) -> S
    ): CommonPromise<S>
}
expect fun sendRequest(method: String, url: String, user: String? = null, password: String? = null,
                       acceptJsonContentType: Boolean = false, body: String? = null): CommonPromise<String>
