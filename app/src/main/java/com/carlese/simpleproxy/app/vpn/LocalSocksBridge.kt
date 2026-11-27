package com.carlese.simpleproxy.app.vpn

import android.util.Base64
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.data.ProxyProtocol
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class LocalSocksBridge(
    private val profile: ProxyProfile,
    private val resolvedConnectHost: String = profile.host.trim(),
    private val protectSocket: (Socket) -> Boolean
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val activeClients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeUdpSockets = Collections.synchronizedSet(mutableSetOf<DatagramSocket>())
    private val isUpstreamIpv4Only = !profile.host.trim().contains(':')
    private val preferDohForDns = AtomicBoolean(true)

    private class DnsQuestionKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DnsQuestionKey) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private class CachedDnsRecord(
        val responseBody: ByteArray,
        val expiresAt: Long
    )

    private val dnsMemoryCache = Collections.synchronizedMap(
        object : LinkedHashMap<DnsQuestionKey, CachedDnsRecord>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<DnsQuestionKey, CachedDnsRecord>?): Boolean {
                return size > 256
            }
        }
    )

    var localPort: Int = 0
        private set

    fun start(): Int {
        val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        localPort = server.localPort
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = bridgeScope

        bridgeScope.launch {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleClient(client, bridgeScope) }
            }
        }
        return localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        dnsMemoryCache.clear()
        synchronized(activeClients) {
            activeClients.forEach { socket -> runCatching { socket.close() } }
            activeClients.clear()
        }
        synchronized(activeUdpSockets) {
            activeUdpSockets.forEach { udp -> runCatching { udp.close() } }
            activeUdpSockets.clear()
        }
        scope?.cancel()
        scope = null
    }

    private suspend fun handleClient(client: Socket, bridgeScope: CoroutineScope) = withContext(Dispatchers.IO) {
        activeClients.add(client)
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val cIn = BufferedInputStream(client.getInputStream())
            val cOut = client.getOutputStream()

            // 1. SOCKS5 method negotiation (local no-auth only)
            val ver = cIn.read()
            val nMethods = cIn.read()
            if (ver != 0x05 || nMethods <= 0) return@withContext
            readExact(cIn, nMethods)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. SOCKS5 request
            val reqVer = cIn.read()
            val cmd = cIn.read()
            cIn.read() // RSV
            val atyp = cIn.read()
            if (reqVer != 0x05) return@withContext

            val targetHost = when (atyp) {
                0x01 -> {
                    val addr = readExact(cIn, 4)
                    InetAddress.getByAddress(addr).hostAddress ?: return@withContext
                }
                0x03 -> {
                    val len = cIn.read()
                    if (len <= 0) return@withContext
                    String(readExact(cIn, len), StandardCharsets.UTF_8)
                }
                0x04 -> {
                    val addr = readExact(cIn, 16)
                    val ipv6Host = InetAddress.getByAddress(addr).hostAddress ?: return@withContext
                    if (isUpstreamIpv4Only) {
                        cOut.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        cOut.flush()
                        return@withContext
                    }
                    ipv6Host
                }
                else -> return@withContext
            }
            val portBytes = readExact(cIn, 2)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x03) {
                // RFC 1928 UDP ASSOCIATE: instrada query DNS (porta 53) in DNS-over-TCP attraverso il proxy
                handleUdpAssociate(client, cIn, cOut, bridgeScope)
                return@withContext
            }

            if (cmd != 0x01) {
                cOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cOut.flush()
                return@withContext
            }

            // 3. Open protected upstream socket to configured proxy server
            val connectTimeout = if (targetPort == 853) 2_500 else 8_000
            upstream = connectUpstream(targetHost, targetPort, connectTimeoutMs = connectTimeout, readTimeoutMs = 10_000)
            upstream.tcpNoDelay = true
            upstream.keepAlive = true
            client.keepAlive = true
            upstream.soTimeout = 0
            client.soTimeout = 0

            // 4. Reply SOCKS5 success
            cOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cOut.flush()

            // 5. Bidirectional relay with clean half-close
            val uIn = upstream.getInputStream()
            val uOut = upstream.getOutputStream()
            val activeUpstream = upstream
            coroutineScope {
                val up = launch {
                    copyStream(cIn, uOut)
                    if (activeUpstream !is SSLSocket) {
                        runCatching { activeUpstream.shutdownOutput() }
                    }
                }
                val down = launch {
                    copyStream(uIn, cOut)
                    runCatching { client.shutdownOutput() }
                }
                up.join()
                down.join()
            }
        } catch (_: Throwable) {
            runCatching {
                client.getOutputStream().write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            }
        } finally {
            activeClients.remove(client)
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private suspend fun handleUdpAssociate(
        client: Socket,
        cIn: InputStream,
        cOut: OutputStream,
        bridgeScope: CoroutineScope
    ) = withContext(Dispatchers.IO) {
        val udpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        activeUdpSockets.add(udpSocket)
        try {
            val udpPort = udpSocket.localPort
            cOut.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((udpPort ushr 8) and 0xFF).toByte(),
                    (udpPort and 0xFF).toByte()
                )
            )
            cOut.flush()
            client.soTimeout = 0

            val readerJob = bridgeScope.launch {
                val buffer = ByteArray(4096)
                while (isActive && !udpSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { udpSocket.receive(packet) }.getOrNull() ?: break
                    val len = packet.length
                    if (len < 10) continue
                    val data = packet.data.copyOf(len)
                    val clientAddr = packet.socketAddress
                    launch {
                        dispatchSocksUdpPacket(udpSocket, clientAddr, data)
                    }
                }
            }

            // RFC 1928: la sessione UDP resta valida finché la connessione TCP di controllo rimane aperta
            while (bridgeScope.isActive && !client.isClosed) {
                val b = runCatching { cIn.read() }.getOrDefault(-1)
                if (b == -1) break
            }
            readerJob.cancel()
        } finally {
            activeUdpSockets.remove(udpSocket)
            runCatching { udpSocket.close() }
        }
    }

    private fun dispatchSocksUdpPacket(
        udpSocket: DatagramSocket,
        clientAddr: SocketAddress,
        packet: ByteArray
    ) {
        if (packet.size < 10) return
        val frag = packet[2].toInt() and 0xFF
        if (frag != 0) return

        val atyp = packet[3].toInt() and 0xFF
        val addrStart = 4
        val addrLen: Int
        val dstHost: String

        when (atyp) {
            0x01 -> {
                addrLen = 4
                dstHost = InetAddress.getByAddress(packet.copyOfRange(4, 8)).hostAddress ?: return
            }
            0x03 -> {
                val domainLen = packet[4].toInt() and 0xFF
                if (domainLen <= 0 || packet.size < 7 + domainLen) return
                addrLen = 1 + domainLen
                dstHost = String(packet, 5, domainLen, StandardCharsets.UTF_8)
            }
            0x04 -> {
                if (packet.size < 22) return
                addrLen = 16
                dstHost = InetAddress.getByAddress(packet.copyOfRange(4, 20)).hostAddress ?: return
            }
            else -> return
        }

        val portOffset = addrStart + addrLen
        val dstPort = ((packet[portOffset].toInt() and 0xFF) shl 8) or (packet[portOffset + 1].toInt() and 0xFF)
        val payloadOffset = portOffset + 2
        if (payloadOffset >= packet.size) return

        // Converte tutte le query UDP DNS (porta 53) in DNS-over-TCP (porta 53) o DoH (porta 443).
        // Eventuale altro traffico UDP (es. QUIC 443) viene scartato rapidamente per il fallback TCP.
        if (dstPort != 53) return

        val dnsQuery = packet.copyOfRange(payloadOffset, packet.size)
        if (dnsQuery.size < 12) return

        val dnsResp = resolveDnsViaProxy(dstHost, dnsQuery) ?: return
        val headerLen = payloadOffset
        val udpReply = ByteArray(headerLen + dnsResp.size)
        packet.copyInto(udpReply, 0, 0, headerLen)
        udpReply[0] = 0x00
        udpReply[1] = 0x00
        udpReply[2] = 0x00
        dnsResp.copyInto(udpReply, headerLen)
        runCatching {
            udpSocket.send(DatagramPacket(udpReply, udpReply.size, clientAddr))
        }
    }

    private fun extractQuestionKey(query: ByteArray): DnsQuestionKey? {
        if (query.size < 17) return null
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) != 0) {
                offset += 2
                break
            }
            offset += 1 + len
        }
        val questionEnd = offset + 4
        if (questionEnd > query.size) return null
        return DnsQuestionKey(query.copyOfRange(12, questionEnd))
    }

    private fun getCachedDnsResponse(query: ByteArray): ByteArray? {
        val key = extractQuestionKey(query) ?: return null
        val entry = dnsMemoryCache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            dnsMemoryCache.remove(key)
            return null
        }
        val resp = ByteArray(2 + entry.responseBody.size)
        resp[0] = query[0]
        resp[1] = query[1]
        entry.responseBody.copyInto(resp, 2)
        return resp
    }

    private fun putCachedDnsResponse(query: ByteArray, response: ByteArray) {
        if (response.size <= 2) return
        val key = extractQuestionKey(query) ?: return
        val body = response.copyOfRange(2, response.size)
        val ttlMs = 60_000L
        dnsMemoryCache[key] = CachedDnsRecord(body, System.currentTimeMillis() + ttlMs)
    }

    private fun isAaaaDnsQuery(query: ByteArray): Boolean {
        if (query.size < 17) return false
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount <= 0) return false
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) != 0) {
                offset += 2
                break
            }
            offset += 1 + len
        }
        if (offset + 4 <= query.size) {
            val qtype = ((query[offset].toInt() and 0xFF) shl 8) or (query[offset + 1].toInt() and 0xFF)
            return qtype == 28
        }
        return false
    }

    private fun createDnsNoDataResponse(query: ByteArray): ByteArray? {
        if (query.size < 17) return null
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) != 0) {
                offset += 2
                break
            }
            offset += 1 + len
        }
        val questionEnd = offset + 4
        if (questionEnd > query.size) return null

        val resp = ByteArray(questionEnd)
        query.copyInto(resp, 0, 0, questionEnd)
        val rdFlag = query[2].toInt() and 0x01
        resp[2] = (0x80 or rdFlag).toByte()
        resp[3] = 0x80.toByte()
        resp[6] = 0
        resp[7] = 0
        resp[8] = 0
        resp[9] = 0
        resp[10] = 0
        resp[11] = 0
        return resp
    }

    private fun resolveDnsViaProxy(dstHost: String, dnsQuery: ByteArray): ByteArray? {
        if (isUpstreamIpv4Only && isAaaaDnsQuery(dnsQuery)) {
            val nodata = createDnsNoDataResponse(dnsQuery)
            if (nodata != null) return nodata
        }

        val cached = getCachedDnsResponse(dnsQuery)
        if (cached != null) return cached

        val isKnownDoh = dstHost in KNOWN_DOH_IPS
        if (isKnownDoh || preferDohForDns.get()) {
            val dohTarget = if (isKnownDoh) dstHost else "1.1.1.1"
            runCatching {
                val resp = queryDnsOverHttps(dohTarget, dnsQuery)
                putCachedDnsResponse(dnsQuery, resp)
                return resp
            }
        }

        runCatching {
            val resp = queryDnsOverTcp(dstHost, dnsQuery)
            putCachedDnsResponse(dnsQuery, resp)
            return resp
        }

        runCatching {
            val resp = queryDnsOverHttps("1.1.1.1", dnsQuery)
            putCachedDnsResponse(dnsQuery, resp)
            return resp
        }
        return null
    }

    private fun queryDnsOverTcp(dstHost: String, dnsQuery: ByteArray): ByteArray {
        var upstream: Socket? = null
        try {
            upstream = connectUpstream(dstHost, 53, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
            upstream.tcpNoDelay = true

            val uOut = upstream.getOutputStream()
            val uIn = BufferedInputStream(upstream.getInputStream())

            val framedQuery = ByteArray(2 + dnsQuery.size)
            framedQuery[0] = ((dnsQuery.size ushr 8) and 0xFF).toByte()
            framedQuery[1] = (dnsQuery.size and 0xFF).toByte()
            dnsQuery.copyInto(framedQuery, 2)
            uOut.write(framedQuery)
            uOut.flush()

            val lenBytes = readExact(uIn, 2)
            val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (respLen <= 0) {
                throw IllegalStateException("Invalid DNS-over-TCP length: $respLen")
            }
            return readExact(uIn, respLen)
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun queryDnsOverHttps(dohHost: String, dnsQuery: ByteArray): ByteArray {
        var upstream: Socket? = null
        var tls: SSLSocket? = null
        try {
            upstream = connectUpstream(dohHost, 443, connectTimeoutMs = 4_000, readTimeoutMs = 5_000)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = factory.createSocket(upstream, dohHost, 443, true) as SSLSocket
            tls.soTimeout = 5_000
            tls.startHandshake()

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(dohHost, tls.session)) {
                throw IllegalStateException("DoH TLS hostname mismatch for $dohHost")
            }

            val reqHeaders = StringBuilder()
                .append("POST /dns-query HTTP/1.1\r\n")
                .append("Host: ").append(dohHost).append("\r\n")
                .append("Content-Type: application/dns-message\r\n")
                .append("Accept: application/dns-message\r\n")
                .append("Content-Length: ").append(dnsQuery.size).append("\r\n")
                .append("Connection: close\r\n\r\n")
                .toString()
                .toByteArray(StandardCharsets.ISO_8859_1)

            val out = tls.getOutputStream()
            out.write(reqHeaders)
            out.write(dnsQuery)
            out.flush()

            val input = BufferedInputStream(tls.getInputStream())
            val rawHeader = readHttpResponseHeader(input)
            val lines = rawHeader.split("\r\n")
            val status = lines.firstOrNull().orEmpty()
            if (!isHttp200Status(status)) {
                throw IllegalStateException("DoH HTTP status: $status")
            }

            var contentLength = -1
            var isChunked = false
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key == "content-length") {
                    contentLength = value.toIntOrNull() ?: -1
                } else if (key == "transfer-encoding" && value.lowercase().contains("chunked")) {
                    isChunked = true
                }
            }

            return when {
                isChunked -> readChunkedHttpBody(input)
                contentLength in 1..65535 -> readExact(input, contentLength)
                else -> readBoundedHttpBody(input)
            }.also { body ->
                if (body.size < 12) throw IllegalStateException("Empty DoH DNS payload")
            }
        } finally {
            runCatching { tls?.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun readChunkedHttpBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input).trim()
            val semi = sizeLine.indexOf(';')
            val hexPart = (if (semi >= 0) sizeLine.substring(0, semi) else sizeLine).trim()
            val chunkSize = hexPart.toIntOrNull(16)
                ?: throw IllegalStateException("Invalid DoH chunk size: $sizeLine")
            if (chunkSize == 0) break
            if (chunkSize < 0 || out.size() + chunkSize > 65535) {
                throw IllegalStateException("DoH chunk too large")
            }
            out.write(readExact(input, chunkSize))
            readAsciiLine(input) // CRLF dopo chunk
        }
        return out.toByteArray()
    }

    private fun readBoundedHttpBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < 65535) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 1024) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun connectUpstream(
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 10_000
    ): Socket {
        val raw = Socket()
        protectSocket(raw)
        raw.tcpNoDelay = true
        raw.keepAlive = true
        val connectHost = resolvedConnectHost.ifBlank { profile.host.trim() }
        raw.connect(InetSocketAddress(connectHost, profile.port), connectTimeoutMs)
        raw.soTimeout = readTimeoutMs

        return when (profile.protocol) {
            ProxyProtocol.HTTP -> {
                performHttpConnect(raw, targetHost, targetPort)
                raw
            }
            ProxyProtocol.HTTPS -> {
                val tlsHost = profile.host.trim()
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(raw, tlsHost, profile.port, true) as SSLSocket
                tls.soTimeout = readTimeoutMs
                tls.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(tlsHost, tls.session)) {
                    runCatching { tls.close() }
                    throw IllegalStateException("HTTPS proxy TLS hostname mismatch for $tlsHost")
                }
                performHttpConnect(tls, targetHost, targetPort)
                tls
            }
            ProxyProtocol.SOCKS4 -> {
                performSocks4Connect(raw, targetHost, targetPort)
                raw
            }
            ProxyProtocol.SOCKS5 -> {
                performSocks5Connect(raw, targetHost, targetPort)
                raw
            }
        }
    }

    private fun performHttpConnect(socket: Socket, targetHost: String, targetPort: Int) {
        val cleanTarget = targetHost.trim()
        require(cleanTarget.isNotEmpty() && !cleanTarget.any { it == '\r' || it == '\n' || it.isWhitespace() }) {
            "Invalid proxy target host"
        }
        val formattedTarget = if (cleanTarget.contains(':')) "[$cleanTarget]:$targetPort" else "$cleanTarget:$targetPort"
        val sb = StringBuilder()
            .append("CONNECT ").append(formattedTarget).append(" HTTP/1.1\r\n")
            .append("Host: ").append(formattedTarget).append("\r\n")

        if (profile.username.isNotEmpty() || profile.password.isNotEmpty()) {
            require(!profile.username.any { it == '\r' || it == '\n' } && !profile.password.any { it == '\r' || it == '\n' }) {
                "Invalid proxy credentials"
            }
            val creds = "${profile.username}:${profile.password}".toByteArray(StandardCharsets.UTF_8)
            val encoded = Base64.encodeToString(creds, Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
        }
        sb.append("\r\n")

        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()

        val input = socket.getInputStream()
        val header = readHttpResponseHeader(input)
        val statusLine = header.lineSequence().firstOrNull().orEmpty()
        if (!isHttp200Status(statusLine)) {
            throw IllegalStateException("HTTP CONNECT refused: $statusLine")
        }
    }

    private fun isHttp200Status(statusLine: String): Boolean {
        val parts = statusLine.trim().split(Regex("\\s+"), limit = 3)
        return parts.size >= 2 && parts[0].startsWith("HTTP/1.") && parts[1] == "200"
    }

    private fun performSocks4Connect(socket: Socket, targetHost: String, targetPort: Int) {
        val userBytes = profile.username.toByteArray(StandardCharsets.UTF_8)
        val parsedIp = parseIpv4Bytes(targetHost)

        val out = socket.getOutputStream()
        out.write(0x04) // VN = 4
        out.write(0x01) // CD = CONNECT
        out.write((targetPort ushr 8) and 0xFF)
        out.write(targetPort and 0xFF)
        if (parsedIp != null) {
            out.write(parsedIp)
            out.write(userBytes)
            out.write(0x00)
        } else {
            // SOCKS4a domain resolution
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            out.write(userBytes)
            out.write(0x00)
            out.write(targetHost.toByteArray(StandardCharsets.UTF_8))
            out.write(0x00)
        }
        out.flush()

        val reply = readExact(socket.getInputStream(), 8)
        if (reply[1] != 0x5A.toByte()) {
            throw IllegalStateException("SOCKS4 CONNECT failed: code=${reply[1].toInt() and 0xFF}")
        }
    }

    private fun performSocks5Connect(socket: Socket, targetHost: String, targetPort: Int) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()

        if (profile.username.isNotEmpty() || profile.password.isNotEmpty()) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val greeting = readExact(input, 2)
        if (greeting[0] != 0x05.toByte()) {
            throw IllegalStateException("Invalid SOCKS5 version")
        }
        when (greeting[1].toInt() and 0xFF) {
            0x00 -> Unit
            0x02 -> {
                val uBytes = profile.username.toByteArray(StandardCharsets.UTF_8).take(255).toByteArray()
                val pBytes = profile.password.toByteArray(StandardCharsets.UTF_8).take(255).toByteArray()
                val auth = ByteArray(3 + uBytes.size + pBytes.size)
                auth[0] = 0x01
                auth[1] = uBytes.size.toByte()
                uBytes.copyInto(auth, 2)
                auth[2 + uBytes.size] = pBytes.size.toByte()
                pBytes.copyInto(auth, 3 + uBytes.size)
                out.write(auth)
                out.flush()
                val authReply = readExact(input, 2)
                if (authReply[1] != 0x00.toByte()) {
                    throw IllegalStateException("SOCKS5 authentication failed")
                }
            }
            else -> throw IllegalStateException("SOCKS5 auth method unsupported")
        }

        val ipv4 = parseIpv4Bytes(targetHost)
        val ipv6 = if (ipv4 == null && targetHost.contains(':')) {
            runCatching { (InetAddress.getByName(targetHost) as? Inet6Address)?.address }.getOrNull()
        } else null

        val addrPayload = when {
            ipv4 != null -> byteArrayOf(0x01) + ipv4
            ipv6 != null -> byteArrayOf(0x04) + ipv6
            else -> {
                val domain = targetHost.toByteArray(StandardCharsets.UTF_8)
                require(domain.size in 1..255) { "Invalid SOCKS5 domain length: ${domain.size}" }
                byteArrayOf(0x03, domain.size.toByte()) + domain
            }
        }

        val req = ByteArray(3 + addrPayload.size + 2)
        req[0] = 0x05
        req[1] = 0x01 // CONNECT
        req[2] = 0x00
        addrPayload.copyInto(req, 3)
        req[req.size - 2] = ((targetPort ushr 8) and 0xFF).toByte()
        req[req.size - 1] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val head = readExact(input, 4)
        if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 CONNECT failed: code=${head[1].toInt() and 0xFF}")
        }
        when (head[3].toInt() and 0xFF) {
            0x01 -> readExact(input, 4)
            0x03 -> {
                val len = input.read()
                if (len > 0) readExact(input, len)
            }
            0x04 -> readExact(input, 16)
        }
        readExact(input, 2)
    }

    private fun parseIpv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255 || parts[i] != v.toString()) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun readHttpResponseHeader(input: InputStream): String {
        val buf = StringBuilder()
        while (buf.length < 8192) {
            val b = input.read()
            if (b == -1) throw EOFException("Unexpected EOF from HTTP proxy")
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) break
        }
        return buf.toString()
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n == -1) throw EOFException("Stream ended early")
            offset += n
        }
        return buffer
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
            // Connection closed
        }
    }

    companion object {
        private val KNOWN_DOH_IPS = setOf(
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "94.140.14.14", "94.140.15.15"
        )

        fun probeProxyPath(
            profile: ProxyProfile,
            resolvedConnectHost: String,
            targetHost: String,
            targetPort: Int,
            protectSocket: (Socket) -> Boolean
        ): Result<Unit> {
            val probe = LocalSocksBridge(profile, resolvedConnectHost, protectSocket)
            var socket: Socket? = null
            return runCatching {
                socket = probe.connectUpstream(
                    targetHost = targetHost,
                    targetPort = targetPort,
                    connectTimeoutMs = 4_000,
                    readTimeoutMs = 4_000
                )
                Unit
            }.also {
                runCatching { socket?.close() }
            }
        }
    }
}
