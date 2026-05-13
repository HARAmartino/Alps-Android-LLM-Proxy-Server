package com.llmproxy.server

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyRequestMapperTest {
    @Test
    fun `buildUpstreamUrl appends path segments and query`() {
        val url = ProxyRequestMapper.buildUpstreamUrl(
            baseUrl = "https://example.com/v1",
            pathSegments = listOf("chat", "completions"),
            queryString = "stream=true",
        )

        assertEquals("https://example.com/v1/chat/completions?stream=true", url.toString())
    }

    @Test
    fun `sanitizeRequestHeaders replaces authorization and host`() {
        val headers = Headers.build {
            append(HttpHeaders.Authorization, "old-key")
            append(HttpHeaders.Host, "old-host")
            append("X-Test", "value")
        }

        val sanitized = ProxyRequestMapper.sanitizeRequestHeaders(
            incomingHeaders = headers,
            apiKey = "new-key",
            targetUrl = ProxyRequestMapper.buildUpstreamUrl("https://proxy.example", emptyList(), ""),
        )

        assertEquals("new-key", sanitized[HttpHeaders.Authorization])
        assertEquals("proxy.example", sanitized[HttpHeaders.Host])
        assertEquals("value", sanitized["X-Test"])
    }

    @Test
    fun `sanitizeResponseHeaders removes content length and hop by hop headers`() {
        val headers = Headers.build {
            append(HttpHeaders.ContentLength, "25")
            append(HttpHeaders.Connection, "keep-alive")
            append("X-Upstream", "ok")
        }

        val sanitized = ProxyRequestMapper.sanitizeResponseHeaders(headers)

        assertNull(sanitized[HttpHeaders.ContentLength])
        assertNull(sanitized[HttpHeaders.Connection])
        assertEquals("ok", sanitized["X-Upstream"])
    }
}
