package com.llmproxy.client.tunneling

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelingClientTest {

    @Test
    fun `retries on 429 with exponential backoff before succeeding`() = runTest {
        var attempts = 0
        val engine = MockEngine { _ ->
            attempts++
            when (attempts) {
                1, 2 -> respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )

                else -> respond(
                    content = """{"public_url":"https://example.ngrok.io","name":"test-tunnel"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val observedBackoffDelays = mutableListOf<Long>()
        val client = NgrokRestClient(
            testHttpClient = HttpClient(engine),
            delayMillis = { observedBackoffDelays += it },
            tunnelNameGenerator = { "unit-test-tunnel" },
        )

        val session = client.createTunnel(localPort = 8443, authToken = "test-token")

        assertEquals("https://example.ngrok.io", session.publicUrl)
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 4_000L), observedBackoffDelays)
    }
}
