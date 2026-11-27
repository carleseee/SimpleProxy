package com.carlese.simpleproxy.app.ui

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.ui.theme.tvFocusRing
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    profiles: List<ProxyProfile>,
    activeProfileId: String?,
    connectedProfileId: String?,
    isVpnRunning: Boolean,
    isVpnConnecting: Boolean,
    vpnError: String?,
    isGoogleConnected: Boolean,
    googlePhotoUrl: String?,
    googleInitial: String,
    strings: AppStrings,
    onToggleVpn: () -> Unit,
    onDismissVpnError: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (ProxyProfile) -> Unit,
    onAddProfileClick: () -> Unit,
    onGoogleAccountClick: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val context = LocalContext.current
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val isWideOrTv = with(density) { windowSize.width.toDp() } >= 720.dp
    val isTvDevice = remember(context) {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val activeProfile = remember(profiles, activeProfileId, connectedProfileId, isVpnRunning, isVpnConnecting) {
        val selectedProfile = profiles.find { it.id == activeProfileId }
        val connectedProfile = profiles.find { it.id == connectedProfileId }
        if (isVpnRunning || isVpnConnecting) connectedProfile ?: selectedProfile else selectedProfile
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val powerButtonFocusRequester = remember { FocusRequester() }
    val addProxyFocusRequester = remember { FocusRequester() }
    var initialTvFocusApplied by remember { mutableStateOf(false) }
    var emptyTvFocusApplied by remember { mutableStateOf(false) }

    LaunchedEffect(isTvDevice, activeProfile != null, profiles.isEmpty()) {
        if (!isTvDevice || initialTvFocusApplied) return@LaunchedEffect
        if (activeProfile != null) {
            withFrameNanos { }
            runCatching { powerButtonFocusRequester.requestFocus() }
            initialTvFocusApplied = true
        } else if (profiles.isEmpty() && !emptyTvFocusApplied) {
            delay(300)
            if (profiles.isEmpty()) {
                withFrameNanos { }
                runCatching { addProxyFocusRequester.requestFocus() }
                emptyTvFocusApplied = true
            }
        }
    }

    LaunchedEffect(vpnError) {
        if (!vpnError.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = vpnError,
                duration = SnackbarDuration.Long
            )
            onDismissVpnError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Simple Proxy", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (isGoogleConnected) {
                        Surface(
                            onClick = onGoogleAccountClick,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(34.dp)
                                .tvFocusRing(CircleShape)
                        ) {
                            if (!googlePhotoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = googlePhotoUrl,
                                    contentDescription = strings.googleAccount,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = googleInitial,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = onOpenOptions,
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = strings.options)
                    }
                }
            )
        },
        floatingActionButton = {
            if (isWideOrTv) {
                ExtendedFloatingActionButton(
                    onClick = onAddProfileClick,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(strings.addProxy) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .focusRequester(addProxyFocusRequester)
                        .tvFocusRing(RoundedCornerShape(16.dp))
                )
            } else {
                FloatingActionButton(
                    onClick = onAddProfileClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .focusRequester(addProxyFocusRequester)
                        .tvFocusRing(RoundedCornerShape(16.dp))
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = strings.addProxy)
                }
            }
        }
    ) { padding ->
        if (isWideOrTv) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(0.42f)) {
                    Spacer(Modifier.height(34.dp))
                    VpnPowerButton(
                        isRunning = isVpnRunning,
                        isConnecting = isVpnConnecting,
                        activeProfile = activeProfile,
                        strings = strings,
                        focusRequester = powerButtonFocusRequester,
                        onToggle = onToggleVpn
                    )
                }
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    Text(
                        text = strings.proxyHeader,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    ProxyList(profiles, activeProfileId, strings, onSelectProfile, onEditProfile)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                VpnPowerButton(
                    isRunning = isVpnRunning,
                    isConnecting = isVpnConnecting,
                    activeProfile = activeProfile,
                    strings = strings,
                    focusRequester = powerButtonFocusRequester,
                    onToggle = onToggleVpn
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = strings.proxyHeader,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                ProxyList(profiles, activeProfileId, strings, onSelectProfile, onEditProfile)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VpnPowerButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    activeProfile: ProxyProfile?,
    strings: AppStrings,
    focusRequester: FocusRequester,
    onToggle: () -> Unit
) {
    val checked = isRunning || isConnecting
    val focusCorner by animateDpAsState(if (checked) 26.dp else 52.dp, label = "focusCorner")

    OutlinedCard(
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FilledIconToggleButton(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = isRunning || isConnecting || activeProfile != null,
                shapes = IconToggleButtonShapes(
                    shape = RoundedCornerShape(52.dp),
                    pressedShape = RoundedCornerShape(34.dp),
                    checkedShape = RoundedCornerShape(26.dp)
                ),
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .size(104.dp)
                    .focusRequester(focusRequester)
                    .tvFocusRing(RoundedCornerShape(focusCorner))
            ) {
                if (isConnecting) {
                    LoadingIndicator(
                        modifier = Modifier.size(46.dp),
                        color = LocalContentColor.current
                    )
                } else {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
                        contentDescription = if (isRunning) "Stop" else "Start",
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnecting -> Color(0xFFF59E0B)
                                isRunning -> Color(0xFF22C55E)
                                else -> Color(0xFFEF4444)
                            }
                        )
                )
                Text(
                    text = when {
                        isConnecting -> strings.connecting
                        isRunning -> strings.connected
                        else -> strings.disconnected
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = activeProfile?.let { "${it.name} • ${it.protocol.name} • ${it.host}:${it.port}" } ?: strings.selectProxy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProxyList(
    profiles: List<ProxyProfile>,
    activeProfileId: String?,
    strings: AppStrings,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (ProxyProfile) -> Unit
) {
    if (profiles.isEmpty()) {
        OutlinedCard(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = strings.emptyState,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        items(profiles, key = { it.id }, contentType = { "proxy_item" }) { profile ->
            val selected = profile.id == activeProfileId
            var focused by remember { mutableStateOf(false) }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                border = when {
                    focused -> BorderStroke(2.5.dp, MaterialTheme.colorScheme.onSurface)
                    selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusRing(RoundedCornerShape(18.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable { onSelectProfile(profile.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.protocol.name} · ${profile.host}:${profile.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { onEditProfile(profile) },
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Text(strings.edit)
                    }
                }
            }
        }
    }
}
