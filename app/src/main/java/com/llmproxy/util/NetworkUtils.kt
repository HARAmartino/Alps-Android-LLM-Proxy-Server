package com.llmproxy.util

import android.content.Context
import java.net.NetworkInterface

object NetworkUtils {
    fun formatLocalEndpoint(context: Context, bindAddress: String, port: Int): String {
        val address = if (bindAddress == "0.0.0.0") {
            findDeviceIpv4Address().ifBlank { "127.0.0.1" }
        } else {
            bindAddress
        }
        return "https://$address:$port"
    }

    private fun findDeviceIpv4Address(): String {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { address ->
                !address.isLoopbackAddress && address.hostAddress?.contains(':') == false
            }
            ?.hostAddress
            .orEmpty()
    }
}
