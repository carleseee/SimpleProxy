package com.carlese.simpleproxy.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carlese.simpleproxy.app.MainActivity
import com.carlese.simpleproxy.app.R
import com.carlese.simpleproxy.app.data.AppDnsOption
import com.carlese.simpleproxy.app.data.AppLanguage
import com.carlese.simpleproxy.app.data.AppSettings
import com.carlese.simpleproxy.app.data.ProxyDatabase
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.data.ProxyProtocol
import com.carlese.simpleproxy.app.data.SettingsRepository
import com.carlese.simpleproxy.app.data.SplitTunnelMode
import com.carlese.simpleproxy.app.data.isValidIpv4Address
import com.carlese.simpleproxy.app.ui.stringsFor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.hevtunnel.TProxyService
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class SimpleProxyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()
    private val operationSeq = AtomicLong(0L)
    private var tunInterface: ParcelFileDescriptor? = null
    private var localBridge: LocalSocksBridge? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var startJob: Job? = null
    private var reconnectJob: Job? = null
    private var diagnosticJob: Job? = null
    @Volatile
    private var activePhysicalNetwork: Network? = null
    @Volatile
    private var nativeTunnelStarted: Boolean = false
    @Volatile
    private var activeProbeTcp: Socket? = null
    @Volatile
    private var activeProbeUdp: DatagramSocket? = null
    private var currentProfile: ProxyProfile? = null
    private var connectedSinceMillis: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val stopSeq = operationSeq.incrementAndGet()
                abortActiveConnectionAttempts()
                clearLiveStateAndNotification()
                serviceScope.launch {
                    tunnelMutex.withLock {
                        stopTunnelLocked(expectedSeq = stopSeq)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val settings = SettingsRepository.getInstance(applicationContext).settings.value
                val requestedId = intent?.getStringExtra(EXTRA_PROFILE_ID)
                if (intent == null && (prepare(this) != null || settings.activeProfileId.isNullOrBlank())) {
                    clearLiveStateAndNotification()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val startSeq = operationSeq.incrementAndGet()
                showForegroundNotification(
                    profile = currentProfile,
                    settings = settings,
                    sinceMillis = connectedSinceMillis
                )
                startJob?.cancel()
                startJob = serviceScope.launch {
                    tunnelMutex.withLock {
                        if (operationSeq.get() == startSeq) {
                            startOrRestartTunnelLocked(requestedId, startSeq)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        val stopSeq = operationSeq.incrementAndGet()
        abortActiveConnectionAttempts()
        clearLiveStateAndNotification()
        serviceScope.launch {
            tunnelMutex.withLock {
                stopTunnelLocked(expectedSeq = stopSeq)
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        operationSeq.incrementAndGet()
        abortActiveConnectionAttempts()
        unregisterNetworkWatcher()
        teardownNativeAndTun()
        _isConnecting.value = false
        _isRunning.value = false
        _connectedProfileId.value = null
        requestTileRefresh()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun abortActiveConnectionAttempts() {
        startJob?.cancel()
        startJob = null
        reconnectJob?.cancel()
        diagnosticJob?.cancel()
        runCatching { activeProbeTcp?.close() }
        runCatching { activeProbeUdp?.close() }
        activeProbeTcp = null
        activeProbeUdp = null
    }

    private fun clearLiveStateAndNotification() {
        _isConnecting.value = false
        _isRunning.value = false
        _connectedProfileId.value = null
        requestTileRefresh()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private suspend fun startOrRestartTunnelLocked(requestedProfileId: String?, seq: Long) {
        if (operationSeq.get() != seq) return
        val repo = SettingsRepository.getInstance(applicationContext)
        val settings = repo.settings.value
        val targetId = requestedProfileId ?: settings.activeProfileId

        val profiles = ProxyDatabase.getInstance(applicationContext).proxyDao().getAllOnce()
        val profile = profiles.find { it.id == targetId } ?: profiles.firstOrNull()
        if (profile == null) {
            _errorMessage.value = if (settings.language == AppLanguage.IT) {
                "Nessun profilo proxy disponibile."
            } else {
                "No proxy profile available."
            }
            stopTunnelLocked()
            return
        }

        val strings = stringsFor(settings.language)
        val selectedTunnelApps = settings.excludedApps.filter { it.isNotBlank() && it != packageName }
        if (settings.splitTunnelMode == SplitTunnelMode.INCLUDE && selectedTunnelApps.isEmpty()) {
            _errorMessage.value = strings.vpnIncludeNeedsSelection
            stopTunnelLocked()
            return
        }
        val dnsServers = resolveDnsServers(settings)
        if (dnsServers.isEmpty()) {
            _errorMessage.value = strings.vpnInvalidCustomDns
            stopTunnelLocked()
            return
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        val preEstablishPhysical = findCurrentPhysicalNetwork(cm) ?: activePhysicalNetwork

        teardownNativeAndTun(cancelReconnect = false)
        _connectedProfileId.value = profile.id
        _isConnecting.value = true
        requestTileRefresh()

        try {
            val previousProfileId = currentProfile?.id
            currentProfile = profile
            if (previousProfileId != profile.id || connectedSinceMillis == null) {
                connectedSinceMillis = null
            }
            showForegroundNotification(
                profile = profile,
                settings = settings,
                sinceMillis = connectedSinceMillis
            )

            val resolvedProxyHost = resolveHostIpv4Preferred(profile.host)
            if (operationSeq.get() != seq) return

            // Per SOCKS5 con UDP end-to-end funzionante usiamo connessione nativa diretta.
            // Per HTTP, HTTPS, SOCKS4 e SOCKS5 TCP-only instradiamo su LocalSocksBridge con
            // UDP ASSOCIATE -> DNS-over-TCP (53) e fallback DoH (443).
            val socks5Probe = if (profile.protocol == ProxyProtocol.SOCKS5) {
                probeSocks5UdpDnsSupport(
                    proxyHost = resolvedProxyHost,
                    proxyPort = profile.port,
                    username = profile.username,
                    password = profile.password,
                    dnsServerIp = dnsServers.first()
                )
            } else {
                Socks5UdpProbeResult(supported = false, udpOverrideAddress = null)
            }
            if (operationSeq.get() != seq) return
            val useDirectSocks5 = socks5Probe.supported && !settings.blockQuic

            val (socksHost, socksPort) = if (useDirectSocks5) {
                resolvedProxyHost to profile.port
            } else {
                val bridge = LocalSocksBridge(
                    profile = profile,
                    resolvedConnectHost = resolvedProxyHost
                ) { socket -> protect(socket) }
                val port = bridge.start()
                localBridge = bridge
                "127.0.0.1" to port
            }
            if (operationSeq.get() != seq) {
                teardownNativeAndTun()
                return
            }

            val builder = Builder()
                .setSession(profile.name)
                .setMtu(1400)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
            if (settings.blockIpv6) {
                builder.addAddress("fc00::1", 128)
                builder.addRoute("::", 0)
            }

            dnsServers.forEach { dns ->
                builder.addDnsServer(dns)
            }
            if (settings.splitTunnelMode == SplitTunnelMode.EXCLUDE) {
                runCatching { builder.addDisallowedApplication(packageName) }
                settings.excludedApps.forEach { excludedPkg ->
                    if (excludedPkg.isNotBlank() && excludedPkg != packageName) {
                        runCatching { builder.addDisallowedApplication(excludedPkg) }
                    }
                }
            } else {
                var allowedCount = 0
                settings.excludedApps
                    .filter { it.isNotBlank() && it != packageName }
                    .forEach { includedPkg ->
                        if (runCatching { builder.addAllowedApplication(includedPkg) }.isSuccess) {
                            allowedCount++
                        }
                    }
                check(allowedCount > 0) { strings.vpnIncludeNeedsSelection }
            }

            if (operationSeq.get() != seq) {
                teardownNativeAndTun()
                return
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException(strings.vpnPermissionDenied)
            if (operationSeq.get() != seq) {
                runCatching { pfd.close() }
                teardownNativeAndTun()
                return
            }
            tunInterface = pfd

            activePhysicalNetwork = preEstablishPhysical
            runCatching {
                setUnderlyingNetworks(preEstablishPhysical?.let { arrayOf(it) })
            }

            val configText = buildHevConfig(
                address = socksHost,
                port = socksPort,
                username = if (useDirectSocks5) profile.username else "",
                password = if (useDirectSocks5) profile.password else "",
                udpOverrideAddress = if (useDirectSocks5) socks5Probe.udpOverrideAddress else null
            )
            val configFile = File(filesDir, "hev_tunnel.yml")
            configFile.writeText(configText)

            if (operationSeq.get() != seq) {
                teardownNativeAndTun()
                return
            }

            nativeTunnelStarted = true
            TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)

            if (operationSeq.get() != seq) {
                teardownNativeAndTun()
                return
            }

            val startedAt = connectedSinceMillis ?: System.currentTimeMillis()
            connectedSinceMillis = startedAt
            _errorMessage.value = null
            _isConnecting.value = false
            _isRunning.value = true
            requestTileRefresh()
            showForegroundNotification(
                profile = profile,
                settings = settings,
                sinceMillis = startedAt
            )

            launchStartupDiagnostic(
                profile = profile,
                resolvedProxyHost = resolvedProxyHost,
                physicalNetwork = preEstablishPhysical,
                cm = cm,
                language = settings.language
            )

            if (settings.autoReconnect) {
                registerNetworkWatcher()
            } else {
                unregisterNetworkWatcher()
            }
        } catch (t: Throwable) {
            if (t is CancellationException || operationSeq.get() != seq) {
                teardownNativeAndTun()
                return
            }
            _isConnecting.value = false
            _errorMessage.value = t.localizedMessage ?: "VPN startup failed"
            stopTunnelLocked(expectedSeq = seq)
        }
    }

    private fun stopTunnelLocked(expectedSeq: Long? = null) {
        unregisterNetworkWatcher()
        teardownNativeAndTun()
        if (expectedSeq == null || operationSeq.get() == expectedSeq) {
            currentProfile = null
            connectedSinceMillis = null
            activePhysicalNetwork = null
            _isConnecting.value = false
            _isRunning.value = false
            _connectedProfileId.value = null
            requestTileRefresh()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
            stopSelf()
        }
    }

    private fun requestTileRefresh() {
        runCatching {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, SimpleProxyTileService::class.java)
            )
        }
    }

    private fun teardownNativeAndTun(cancelReconnect: Boolean = true) {
        if (cancelReconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        diagnosticJob?.cancel()
        if (nativeTunnelStarted) {
            nativeTunnelStarted = false
            runCatching { TProxyService.TProxyStopService() }
        }
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { localBridge?.stop() }
        localBridge = null
    }

    private fun launchStartupDiagnostic(
        profile: ProxyProfile,
        resolvedProxyHost: String,
        physicalNetwork: Network?,
        cm: ConnectivityManager,
        language: AppLanguage
    ) {
        diagnosticJob?.cancel()
        diagnosticJob = serviceScope.launch {
            val httpsProbe = LocalSocksBridge.probeProxyPath(
                profile = profile,
                resolvedConnectHost = resolvedProxyHost,
                targetHost = "1.1.1.1",
                targetPort = 443
            ) { socket -> protect(socket) }

            if (!isActive || !_isRunning.value) return@launch

            if (httpsProbe.isFailure) {
                val reason = httpsProbe.exceptionOrNull()?.localizedMessage ?: "connection failed"
                _errorMessage.value = if (language == AppLanguage.IT) {
                    "Proxy non raggiungibile o credenziali errate ($reason)"
                } else {
                    "Proxy unreachable or auth failed ($reason)"
                }
                return@launch
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalNetwork != null) {
                val strictPrivateDns = runCatching {
                    cm.getLinkProperties(physicalNetwork)?.privateDnsServerName
                }.getOrNull()
                if (!strictPrivateDns.isNullOrBlank()) {
                    val dotProbe = LocalSocksBridge.probeProxyPath(
                        profile = profile,
                        resolvedConnectHost = resolvedProxyHost,
                        targetHost = strictPrivateDns,
                        targetPort = 853
                    ) { socket -> protect(socket) }
                    if (isActive && _isRunning.value && dotProbe.isFailure) {
                        _errorMessage.value = if (language == AppLanguage.IT) {
                            "DNS privato Android ($strictPrivateDns:853) bloccato dal proxy. Imposta DNS privato su Automatico o Disattivato."
                        } else {
                            "Android Private DNS ($strictPrivateDns:853) is blocked by proxy. Set Private DNS to Automatic or Off."
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findCurrentPhysicalNetwork(cm: ConnectivityManager): Network? {
        fun isUsablePhysical(net: Network?): NetworkCapabilities? {
            if (net == null) return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            return if (
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                caps
            } else {
                null
            }
        }

        val active = cm.activeNetwork
        if (isUsablePhysical(active) != null) return active

        val candidates = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return candidates
            .mapNotNull { net -> isUsablePhysical(net)?.let { caps -> net to caps } }
            .sortedByDescending { (_, caps) ->
                var score = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 20
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 15
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10
                score
            }
            .firstOrNull()
            ?.first
    }

    private fun showForegroundNotification(
        profile: ProxyProfile?,
        settings: AppSettings,
        sinceMillis: Long?
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = if (settings.persistentNotification) CHANNEL_LOW else CHANNEL_SILENT_MIN

        val channel = NotificationChannel(
            channelId,
            if (settings.language == AppLanguage.IT) "Stato connessione VPN" else "VPN connection status",
            if (settings.persistentNotification) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopVpnReceiver::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val strings = stringsFor(settings.language)
        val title = strings.notifRunning
        val body = profile?.let { "${it.name} • ${it.protocol.name} • ${it.host}:${it.port}" }
            ?: strings.notifTunnelDesc
        val stopLabel = strings.notifDisconnect

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(if (settings.persistentNotification) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .addAction(0, stopLabel, stopIntent)

        if (sinceMillis != null) {
            builder
                .setWhen(sinceMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
        } else {
            builder
                .setShowWhen(false)
                .setUsesChronometer(false)
        }

        val notification: Notification = builder.build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun resolveDnsServers(settings: AppSettings): List<String> {
        if (settings.dnsOption != AppDnsOption.CUSTOM) {
            return settings.dnsOption.addresses
        }
        val raw = settings.customDns.trim()
        return if (isValidIpv4Address(raw)) listOf(raw) else emptyList()
    }

    private fun resolveHostIpv4Preferred(rawHost: String): String {
        val host = rawHost.trim()
        if (host.isEmpty()) return host
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            addresses.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
                ?: host
        }.getOrDefault(host)
    }

    private data class Socks5UdpProbeResult(
        val supported: Boolean,
        val udpOverrideAddress: String?
    )

    private fun probeSocks5UdpDnsSupport(
        proxyHost: String,
        proxyPort: Int,
        username: String,
        password: String,
        dnsServerIp: String
    ): Socks5UdpProbeResult {
        var tcp: Socket? = null
        var udp: DatagramSocket? = null
        return try {
            val dnsBytes = InetAddress.getByName(dnsServerIp).address
            if (dnsBytes.size != 4) return Socks5UdpProbeResult(false, null)

            tcp = Socket().also { activeProbeTcp = it }
            protect(tcp)
            tcp.connect(InetSocketAddress(proxyHost, proxyPort), 1_500)
            tcp.soTimeout = 1_500

            val tIn = tcp.getInputStream()
            val tOut = tcp.getOutputStream()

            if (username.isNotEmpty() || password.isNotEmpty()) {
                tOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                tOut.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            tOut.flush()

            val greeting = readExactBytes(tIn, 2)
            if (greeting[0] != 0x05.toByte()) return Socks5UdpProbeResult(false, null)
            when (greeting[1].toInt() and 0xFF) {
                0x00 -> Unit
                0x02 -> {
                    val uBytes = username.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                    val pBytes = password.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                    val auth = ByteArray(3 + uBytes.size + pBytes.size)
                    auth[0] = 0x01
                    auth[1] = uBytes.size.toByte()
                    uBytes.copyInto(auth, 2)
                    auth[2 + uBytes.size] = pBytes.size.toByte()
                    pBytes.copyInto(auth, 3 + uBytes.size)
                    tOut.write(auth)
                    tOut.flush()
                    val authReply = readExactBytes(tIn, 2)
                    if (authReply[1] != 0x00.toByte()) return Socks5UdpProbeResult(false, null)
                }
                else -> return Socks5UdpProbeResult(false, null)
            }

            tOut.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            tOut.flush()
            val head = readExactBytes(tIn, 4)
            if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) return Socks5UdpProbeResult(false, null)

            val relayAddrStr = when (head[3].toInt() and 0xFF) {
                0x01 -> InetAddress.getByAddress(readExactBytes(tIn, 4)).hostAddress
                0x03 -> {
                    val len = tIn.read()
                    if (len <= 0) return Socks5UdpProbeResult(false, null)
                    String(readExactBytes(tIn, len), Charsets.UTF_8)
                }
                0x04 -> InetAddress.getByAddress(readExactBytes(tIn, 16)).hostAddress
                else -> return Socks5UdpProbeResult(false, null)
            } ?: return Socks5UdpProbeResult(false, null)

            val portBytes = readExactBytes(tIn, 2)
            val relayPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            if (relayPort <= 0) return Socks5UdpProbeResult(false, null)

            val isWildcardRelay = relayAddrStr == "0.0.0.0" || relayAddrStr == "::" || relayAddrStr == "0:0:0:0:0:0:0:0"
            val effectiveRelayHost = if (isWildcardRelay) proxyHost else relayAddrStr

            if (!isWildcardRelay && effectiveRelayHost != proxyHost) {
                val relayIsSiteLocal = runCatching { InetAddress.getByName(effectiveRelayHost).isSiteLocalAddress }.getOrDefault(false)
                val proxyIsSiteLocal = runCatching { InetAddress.getByName(proxyHost).isSiteLocalAddress }.getOrDefault(false)
                if (relayIsSiteLocal && !proxyIsSiteLocal) {
                    return Socks5UdpProbeResult(false, null)
                }
            }

            val dnsQuery = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0x00, 0x00, 0x01, 0x00, 0x01
            )
            val socksUdpPacket = ByteArray(10 + dnsQuery.size)
            socksUdpPacket[3] = 0x01
            dnsBytes.copyInto(socksUdpPacket, 4)
            socksUdpPacket[8] = 0x00
            socksUdpPacket[9] = 0x35
            dnsQuery.copyInto(socksUdpPacket, 10)

            udp = DatagramSocket().also { activeProbeUdp = it }
            protect(udp)
            udp.soTimeout = 1_200
            val relayInet = InetAddress.getByName(effectiveRelayHost)
            udp.send(DatagramPacket(socksUdpPacket, socksUdpPacket.size, relayInet, relayPort))

            val recvBuf = ByteArray(512)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            udp.receive(recvPacket)
            val ok = recvPacket.length > 10
            Socks5UdpProbeResult(
                supported = ok,
                udpOverrideAddress = if (ok && isWildcardRelay) proxyHost else null
            )
        } catch (_: Throwable) {
            Socks5UdpProbeResult(false, null)
        } finally {
            activeProbeUdp = null
            activeProbeTcp = null
            runCatching { udp?.close() }
            runCatching { tcp?.close() }
        }
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n == -1) throw EOFException("Stream ended early")
            offset += n
        }
        return buffer
    }

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val current = activePhysicalNetwork
                if (current == null) {
                    activePhysicalNetwork = network
                    runCatching { setUnderlyingNetworks(arrayOf(network)) }
                } else if (current != network) {
                    val newCaps = cm.getNetworkCapabilities(network)
                    val switchedToWifi = newCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    val oldStillAlive = cm.getNetworkCapabilities(current)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (switchedToWifi || !oldStillAlive) {
                        activePhysicalNetwork = network
                        runCatching { setUnderlyingNetworks(arrayOf(network)) }
                        if (_isRunning.value) {
                            scheduleAutoReconnect()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network == activePhysicalNetwork) {
                    activePhysicalNetwork = null
                    runCatching { setUnderlyingNetworks(null) }
                    if (_isRunning.value) {
                        scheduleAutoReconnect()
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    private fun scheduleAutoReconnect() {
        reconnectJob?.cancel()
        val seq = operationSeq.get()
        reconnectJob = serviceScope.launch {
            delay(800)
            if (_isRunning.value && operationSeq.get() == seq) {
                tunnelMutex.withLock {
                    if (_isRunning.value && operationSeq.get() == seq) {
                        startOrRestartTunnelLocked(currentProfile?.id, seq)
                    }
                }
            }
        }
    }

    private fun buildHevConfig(
        address: String,
        port: Int,
        username: String,
        password: String,
        udpOverrideAddress: String? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("tunnel:")
        sb.appendLine("  name: tun0")
        sb.appendLine("  mtu: 1400")
        sb.appendLine("  multi-queue: false")
        sb.appendLine("  ipv4: 198.18.0.1")
        sb.appendLine("  ipv6: 'fc00::1'")
        sb.appendLine("socks5:")
        sb.appendLine("  port: $port")
        sb.appendLine("  address: '${yamlEscape(address)}'")
        sb.appendLine("  udp: 'udp'")
        if (!udpOverrideAddress.isNullOrBlank()) {
            sb.appendLine("  udp-address: '${yamlEscape(udpOverrideAddress)}'")
        }
        if (username.isNotEmpty() || password.isNotEmpty()) {
            sb.appendLine("  username: '${yamlEscape(username)}'")
            sb.appendLine("  password: '${yamlEscape(password)}'")
        }
        sb.appendLine("misc:")
        sb.appendLine("  task-stack-size: 81920")
        sb.appendLine("  connect-timeout: 5000")
        sb.appendLine("  read-write-timeout: 60000")
        sb.appendLine("  log-level: warn")
        return sb.toString()
    }

    private fun yamlEscape(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("'", "''")

    companion object {
        const val ACTION_START = "com.carlese.simpleproxy.app.vpn.START"
        const val ACTION_STOP = "com.carlese.simpleproxy.app.vpn.STOP"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_LOW = "simpleproxy_vpn_low"
        private const val CHANNEL_SILENT_MIN = "simpleproxy_vpn_min"
        private const val NOTIFICATION_ID = 3001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isConnecting = MutableStateFlow(false)
        val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

        private val _connectedProfileId = MutableStateFlow<String?>(null)
        val connectedProfileId: StateFlow<String?> = _connectedProfileId.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        fun clearError() {
            _errorMessage.value = null
        }

        fun start(context: Context, profileId: String?) {
            val intent = Intent(context, SimpleProxyVpnService::class.java).apply {
                action = ACTION_START
                if (profileId != null) putExtra(EXTRA_PROFILE_ID, profileId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, SimpleProxyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            val delivered = runCatching {
                appCtx.startService(intent)
            }.isSuccess
            if (!delivered) {
                _isConnecting.value = false
                _isRunning.value = false
                _connectedProfileId.value = null
                runCatching {
                    appCtx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                }
                runCatching {
                    TileService.requestListeningState(
                        appCtx,
                        ComponentName(appCtx, SimpleProxyTileService::class.java)
                    )
                }
            }
        }
    }
}
