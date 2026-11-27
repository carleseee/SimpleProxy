package com.carlese.simpleproxy.app.data

import android.os.SystemClock
import com.carlese.simpleproxy.app.vpn.LocalSocksBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

data class ProxyCheckResult(
    val proxyIp: String?,
    val deviceIp: String?,
    val latencyMs: Long?,
    val error: String?
)

object ProxyDiagnostics {

    private const val IP_ECHO_HOST = "api.ipify.org"
    private const val IP_ECHO_PATH = "/"

    suspend fun check(profile: ProxyProfile): ProxyCheckResult = withContext(Dispatchers.IO) {
        val resolvedHost = runCatching {
            InetAddress.getAllByName(profile.host.trim())
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull() ?: profile.host.trim()

        val start = SystemClock.elapsedRealtime()
        val proxyResult = runCatching {
            LocalSocksBridge.fetchViaProxy(
                profile = profile,
                resolvedConnectHost = resolvedHost,
                targetHost = IP_ECHO_HOST,
                targetPort = 80,
                path = IP_ECHO_PATH,
                protectSocket = { true }
            ).getOrThrow()
        }
        val elapsed = SystemClock.elapsedRealtime() - start

        ProxyCheckResult(
            proxyIp = proxyResult.getOrNull()?.trim()?.takeIf { it.isNotBlank() },
            deviceIp = fetchDeviceIp(),
            latencyMs = if (proxyResult.isSuccess) elapsed else null,
            error = proxyResult.exceptionOrNull()?.localizedMessage
        )
    }

    private fun fetchDeviceIp(): String? = runCatching {
        val connection = (URL("https://$IP_ECHO_HOST$IP_ECHO_PATH").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SimpleProxy-Diagnostics")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
