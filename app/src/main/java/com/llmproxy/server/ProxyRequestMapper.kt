package com.llmproxy.server

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parametersOf

object ProxyRequestMapper {
    private val hopByHopHeaders = setOf(
        HttpHeaders.Connection,
        HttpHeaders.KeepAlive,
        HttpHeaders.ProxyAuthenticate,
        HttpHeaders.ProxyAuthorization,
        HttpHeaders.TE,
        HttpHeaders.Trailer,
        HttpHeaders.TransferEncoding,
        HttpHeaders.Upgrade,
    )

    fun buildUpstreamUrl(baseUrl: String, pathSegments: List<String>, queryString: String): Url {
        val normalizedBase = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        return URLBuilder(normalizedBase).apply {
            encodedPathSegments = encodedPathSegments.filter { it.isNotBlank() } + pathSegments.filter { it.isNotBlank() }
            parameters.clear()
            if (queryString.isNotBlank()) {
                encodedParameters = parametersOf(queryString = queryString)
            }
        }.build()
    }

    fun sanitizeRequestHeaders(
        incomingHeaders: Headers,
        apiKey: String,
        targetUrl: Url,
    ): Headers {
        return Headers.build {
            incomingHeaders.forEach { name, values ->
                if (name.equals(HttpHeaders.Host, ignoreCase = true) ||
                    name.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    name in hopByHopHeaders
                ) {
                    return@forEach
                }
                values.forEach { append(name, it) }
            }
            append(HttpHeaders.Host, targetUrl.hostWithPort)
            if (apiKey.isNotBlank()) {
                append(HttpHeaders.Authorization, apiKey)
            }
        }
    }

    fun sanitizeResponseHeaders(upstreamHeaders: Headers): Headers {
        return Headers.build {
            upstreamHeaders.forEach { name, values ->
                if (name.equals(HttpHeaders.ContentLength, ignoreCase = true) || name in hopByHopHeaders) {
                    return@forEach
                }
                values.forEach { append(name, it) }
            }
        }
    }
}
