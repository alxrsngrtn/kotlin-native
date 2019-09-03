package org.jetbrains.influxdb

actual fun sendRequest(method: String, url: String, user: String? = null, password: String? = null,
                       acceptJsonContentType: Boolean = false, body: String? = null): Promise<String> {
    val request = require("node-fetch")
    val headers = mutableListOf<Pair<String, String>>()
    if (user != null && password != null) {
        headers.add("Authorization" to getAuth(user, password))
    }
    if (acceptJsonContentType) {
        headers.add("Accept" to "application/json")
    }
    return request(url,
            json(
                    "method" to method.toString(),
                    "headers" to json(*(headers.toTypedArray())),
                    "body" to body
            )
    ).then { response ->
        if (!response.ok)
            error("Error during getting response from $url\n" +
                    "${response}")
        else
            response.text()
    }
}

