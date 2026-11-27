package com.carlese.simpleproxy.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlese.simpleproxy.app.data.AppDnsOption
import com.carlese.simpleproxy.app.data.AppSettings
import com.carlese.simpleproxy.app.data.ProxyCheckResult
import com.carlese.simpleproxy.app.data.ProxyDatabase
import com.carlese.simpleproxy.app.data.ProxyDiagnostics
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.data.ProxyProtocol
import com.carlese.simpleproxy.app.data.SplitTunnelMode
import com.carlese.simpleproxy.app.ui.theme.tvFocusRing
import com.carlese.simpleproxy.app.vpn.SimpleProxyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    settings: AppSettings,
    strings: AppStrings,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVpnRunning by SimpleProxyVpnService.isRunning.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf<ProxyProfile?>(null) }
    var result by remember { mutableStateOf<ProxyCheckResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var lastTest by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    LaunchedEffect(settings.activeProfileId) {
        profile = withContext(Dispatchers.IO) {
            val all = ProxyDatabase.getInstance(context).proxyDao().getAllOnce()
            all.find { it.id == settings.activeProfileId } ?: all.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.diagnosticsTitle, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.cancel)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isVpnRunning) strings.connected else strings.disconnected,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val target = profile ?: return@Button
                                if (testing) return@Button
                                scope.launch {
                                    testing = true
                                    result = null
                                    try {
                                        result = ProxyDiagnostics.check(target)
                                    } finally {
                                        testing = false
                                    }
                                    lastTest = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                }
                            },
                            enabled = profile != null && !testing,
                            modifier = Modifier.tvFocusRing(CircleShape)
                        ) {
                            Text(if (testing) strings.testingConnection else strings.testConnection)
                        }
                    }
                    Text(
                        text = profile?.let { "${it.name} · ${it.protocol} · ${it.host}:${it.port}" }
                            ?: strings.noProxyProfile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DiagnosticCard(strings.proxyIpLabel, result?.proxyIp ?: "—")
            DiagnosticCard(strings.deviceIpLabel, result?.deviceIp ?: "—")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticCard(
                    label = strings.latencyLabel,
                    value = result?.latencyMs?.let { "$it ms" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                DiagnosticCard(
                    label = strings.dnsLabel,
                    value = dnsLabel(settings, strings),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticCard(
                    label = strings.ipv6Label,
                    value = if (isVpnRunning && settings.blockIpv6) strings.blocked else strings.allowed,
                    modifier = Modifier.weight(1f)
                )
                DiagnosticCard(
                    label = strings.udpLabel,
                    value = when {
                        !isVpnRunning -> strings.allowed
                        settings.blockQuic -> strings.blocked
                        profile?.protocol == ProxyProtocol.SOCKS5 -> "SOCKS5"
                        else -> strings.blocked
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            val leakSuspected = settings.leakAlerts &&
                settings.splitTunnelMode == SplitTunnelMode.EXCLUDE &&
                result?.proxyIp != null &&
                result?.proxyIp == result?.deviceIp
            if (leakSuspected) {
                OutlinedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.leakWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            result?.error?.takeIf { it.isNotBlank() }?.let { err ->
                OutlinedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (lastTest != null) {
                Text(
                    text = "${strings.lastTest}: $lastTest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = strings.webRtcBrowserHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://browserleaks.com/webrtc"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.tvFocusRing(CircleShape)
            ) {
                Text(strings.openWebRtcTest)
            }
            Text(
                text = strings.dataUsageUnavailable,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        }
    }
}

private fun dnsLabel(settings: AppSettings, strings: AppStrings): String = when (settings.dnsOption) {
    AppDnsOption.SYSTEM -> strings.dnsSystem
    AppDnsOption.ADGUARD -> strings.dnsAdguard
    AppDnsOption.CLOUDFLARE -> strings.dnsCloudflare
    AppDnsOption.GOOGLE -> strings.dnsGoogle
    AppDnsOption.QUAD9 -> strings.dnsQuad9
    AppDnsOption.OPENDNS -> strings.dnsOpenDns
    AppDnsOption.CUSTOM -> settings.customDns.ifBlank { strings.dnsCustom }
}

@Composable
private fun DiagnosticCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
