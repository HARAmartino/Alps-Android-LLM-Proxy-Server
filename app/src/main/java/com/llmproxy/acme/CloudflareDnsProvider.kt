package com.llmproxy.acme

import com.llmproxy.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CloudflareDnsProvider(
    private val apiTokenProvider: () -> String,
    private val httpClient: HttpClient,
) : DnsProvider {
    override suspend fun addTxtRecord(name: String, content: String) = withContext(Dispatchers.IO) {
        val zoneId = resolveZoneId(name)
        val payload = JSONObject()
            .put("type", "TXT")
            .put("name", normalizeName(name))
            .put("content", content)
            .put("ttl", 120)
            .put("proxied", false)

        val response = httpClient.post("$BASE_URL/zones/$zoneId/dns_records") {
            addHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }.body<String>()
        checkSuccess(response, "create TXT")
    }

    override suspend fun removeTxtRecord(name: String, content: String) = withContext(Dispatchers.IO) {
        val zoneId = resolveZoneId(name)
        val recordsResponse = httpClient.get("$BASE_URL/zones/$zoneId/dns_records") {
            addHeaders()
            url {
                parameters.append("type", "TXT")
                parameters.append("name", normalizeName(name))
            }
        }.body<String>()
        val recordsJson = JSONObject(recordsResponse)
        checkSuccess(recordsResponse, "list TXT")
        val matches = recordsJson.optJSONArray("result").orEmpty()

        for (index in 0 until matches.length()) {
            val record = matches.optJSONObject(index) ?: continue
            if (record.optString("content") != content) continue
            val recordId = record.optString("id")
            if (recordId.isBlank()) continue
            val deleteResponse = httpClient.delete("$BASE_URL/zones/$zoneId/dns_records/$recordId") {
                addHeaders()
            }.body<String>()
            checkSuccess(deleteResponse, "delete TXT")
        }
    }

    private suspend fun resolveZoneId(recordName: String): String {
        val normalized = normalizeName(recordName)
        val parts = normalized.split('.')
        if (parts.size < 2) {
            error("Invalid domain for Cloudflare DNS: $recordName")
        }
        for (start in 0 until parts.size - 1) {
            val candidateZone = parts.drop(start).joinToString(".")
            val response = httpClient.get("$BASE_URL/zones") {
                addHeaders()
                url {
                    parameters.append("name", candidateZone)
                    parameters.append("status", "active")
                }
            }.body<String>()
            val json = JSONObject(response)
            if (!json.optBoolean("success")) continue
            val result = json.optJSONArray("result").orEmpty()
            if (result.length() == 0) continue
            return result.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: error("Cloudflare zone lookup returned empty zone id for $candidateZone")
        }
        error("Unable to resolve Cloudflare zone for $recordName")
    }

    private fun normalizeName(name: String): String = name.trim().trimEnd('.')

    private fun checkSuccess(responseBody: String, action: String) {
        val json = runCatching { JSONObject(responseBody) }.getOrElse {
            error("Cloudflare $action failed with non-JSON response")
        }
        if (json.optBoolean("success")) return
        val errors = json.optJSONArray("errors").orEmpty()
        val details = buildString {
            for (index in 0 until errors.length()) {
                val error = errors.optJSONObject(index) ?: continue
                if (isNotEmpty()) append("; ")
                append(error.optInt("code")).append(": ").append(error.optString("message"))
            }
        }
        Logger.e("CloudflareDnsProvider", "Cloudflare $action failed: $details")
        error("Cloudflare $action failed: $details")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addHeaders() {
        val token = apiTokenProvider().trim()
        require(token.isNotBlank()) { "Cloudflare API token is required." }
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private companion object {
        const val BASE_URL = "https://api.cloudflare.com/client/v4"
    }
}
