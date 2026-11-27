package com.carlese.simpleproxy.app.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

enum class ProxyProtocol { SOCKS5, SOCKS4, HTTP, HTTPS }

enum class AppDnsOption(val addresses: List<String>) {
    SYSTEM(listOf("1.1.1.1", "8.8.8.8")),
    ADGUARD(listOf("94.140.14.14", "94.140.15.15")),
    CLOUDFLARE(listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE(listOf("8.8.8.8", "8.8.4.4")),
    QUAD9(listOf("9.9.9.9", "149.112.112.112")),
    OPENDNS(listOf("208.67.222.222", "208.67.220.220")),
    CUSTOM(emptyList())
}

fun isValidIpv4Address(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    val parts = trimmed.split('.')
    return parts.size == 4 && parts.all { part ->
        val num = part.toIntOrNull()
        num != null && num in 0..255 && part == num.toString()
    }
}

@Immutable
@Entity(tableName = "proxy_profiles")
data class ProxyProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val host: String,
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

object ProxyUriParser {
    fun parse(rawInput: String): ProxyProfile? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val normalized = if (trimmed.contains("://")) trimmed else "socks5://$trimmed"
        return runCatching {
            val uri = URI(normalized)
            val protocol = when (uri.scheme?.lowercase()) {
                "http" -> ProxyProtocol.HTTP
                "https" -> ProxyProtocol.HTTPS
                "socks4", "socks4a" -> ProxyProtocol.SOCKS4
                "socks5", "socks5h", "socks" -> ProxyProtocol.SOCKS5
                else -> return null
            }
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: return null
            val defaultPort = when (protocol) {
                ProxyProtocol.HTTP -> 8080
                ProxyProtocol.HTTPS -> 443
                else -> 1080
            }
            val port = if (uri.port in 1..65535) uri.port else defaultPort
            val userInfo = uri.rawUserInfo.orEmpty()
            val decodeComponent: (String) -> String = { value ->
                runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
            }
            val (user, pass) = if (userInfo.contains(":")) {
                val p = userInfo.split(":", limit = 2)
                decodeComponent(p[0]) to decodeComponent(p[1])
            } else decodeComponent(userInfo) to ""

            ProxyProfile(
                name = "${protocol.name} · $host",
                protocol = protocol,
                host = host,
                port = port,
                username = user,
                password = pass
            )
        }.getOrNull()
    }
}
