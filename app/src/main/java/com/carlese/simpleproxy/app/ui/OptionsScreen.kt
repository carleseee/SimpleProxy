package com.carlese.simpleproxy.app.ui

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.carlese.simpleproxy.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.carlese.simpleproxy.app.data.AppDnsOption
import com.carlese.simpleproxy.app.data.AppLanguage
import com.carlese.simpleproxy.app.data.AppUpdateManager
import com.carlese.simpleproxy.app.data.DownloadedApk

import com.carlese.simpleproxy.app.data.UpdateCheckResult
import kotlinx.coroutines.launch
import com.carlese.simpleproxy.app.data.AppSettings
import com.carlese.simpleproxy.app.data.CloudAccountState
import com.carlese.simpleproxy.app.data.SplitTunnelMode
import com.carlese.simpleproxy.app.data.isValidIpv4Address
import com.carlese.simpleproxy.app.ui.theme.AppAccentColor
import com.carlese.simpleproxy.app.ui.theme.AppThemeMode
import com.carlese.simpleproxy.app.ui.theme.tvFocusRing
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private object DonationLinks {
    const val KO_FI_URL = "https://ko-fi.com/carlese"
    const val BUG_REPORT_URL = "https://github.com/carleseee/SimpleProxy/issues/new"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OptionsScreen(
    settings: AppSettings,
    accountState: CloudAccountState,
    strings: AppStrings,
    onSignInGoogle: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onDismissAccountError: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    openUpdatesOnLaunch: Boolean = false,
    onConsumeOpenUpdates: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val canUseAmoled = settings.themeMode != AppThemeMode.LIGHT
    var showAppExclusionDialog by remember { mutableStateOf(false) }
    var showDonateQrDialog by remember { mutableStateOf(false) }
    var showBugReportDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var forceUpdateCheck by remember { mutableStateOf(true) }

    LaunchedEffect(openUpdatesOnLaunch) {
        if (openUpdatesOnLaunch) {
            forceUpdateCheck = false
            showUpdateDialog = true
            onConsumeOpenUpdates()
        }
    }

    LaunchedEffect(accountState.lastError) {
        val err = accountState.lastError
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            onDismissAccountError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(strings.options, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.tvFocusRing(CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.cancel)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            val optionsListState = rememberLazyListState()
            LazyColumn(
                state = optionsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Account Google
                item(key = "account", contentType = "account_card") {
                    OutlinedCard(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(strings.googleAccount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Icon(
                                    Icons.Rounded.Cloud,
                                    contentDescription = null,
                                    tint = if (accountState.uid != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (accountState.uid != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (!accountState.photoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = accountState.photoUrl,
                                            contentDescription = accountState.email,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                        )
                                    } else {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                        ) {
                                            Text(
                                                text = (
                                                    accountState.email?.trim()?.takeIf { it.isNotEmpty() }
                                                        ?: accountState.displayName?.trim()?.takeIf { it.isNotEmpty() }
                                                        ?: "G"
                                                    ).first().uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = accountState.email ?: accountState.displayName ?: strings.googleConnected,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subtitle = if (!settings.lastSyncTime.isNullOrBlank()) {
                                            "${strings.googleConnected} · ${strings.lastSync}: ${settings.lastSyncTime}"
                                        } else {
                                            strings.googleConnected
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (accountState.needsDriveConsent) {
                                    Surface(
                                        onClick = onSyncNow,
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusRing(RoundedCornerShape(12.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                text = strings.e2eeDriveConsentBadge,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onSyncNow,
                                        enabled = !accountState.isSyncing,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .tvFocusRing(CircleShape),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        if (accountState.isSyncing) {
                                            LoadingIndicator(
                                                modifier = Modifier.size(18.dp),
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(strings.syncing, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(strings.syncNow, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onSignOutGoogle,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .tvFocusRing(CircleShape),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(strings.signOut, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    }
                                }
                            } else {
                                Text(
                                    text = strings.googleDisconnected,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onSignInGoogle,
                                    enabled = !accountState.isSigningIn,
                                    modifier = Modifier.tvFocusRing(CircleShape)
                                ) {
                                    if (accountState.isSigningIn) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(strings.signIn)
                                }
                            }
                        }
                    }
                }

                // Lingua (Menu a tendina alfabetico)
                item(key = "language", contentType = "dropdown_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.language, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        var langMenuExpanded by remember { mutableStateOf(false) }
                        val langItems = remember(settings.language) { languageDropdownItems(settings.language) }
                        val currentLangLabel = langItems.firstOrNull { (lang, _) -> lang == settings.language }?.second ?: "Italiano"

                        Surface(
                            onClick = { langMenuExpanded = !langMenuExpanded },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = languageFlag(settings.language),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = currentLangLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(
                                    imageVector = if (langMenuExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.ArrowDropDown,
                                    contentDescription = strings.language
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = langMenuExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    langItems.forEachIndexed { index, (lang, label) ->
                                        val isSelected = lang == settings.language
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(
                                                    RoundedCornerShape(
                                                        topStart = if (index == 0) 16.dp else 0.dp,
                                                        topEnd = if (index == 0) 16.dp else 0.dp,
                                                        bottomStart = if (index == langItems.lastIndex) 16.dp else 0.dp,
                                                        bottomEnd = if (index == langItems.lastIndex) 16.dp else 0.dp
                                                    )
                                                )
                                                .tvFocusRing(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                                .clickable {
                                                    onUpdateSettings { it.copy(language = lang) }
                                                    langMenuExpanded = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = languageFlag(lang),
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (index < langItems.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                thickness = 0.5.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Tema + Nero puro AMOLED + Colore tema
                item(key = "theme", contentType = "theme_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.theme, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val themeOptions = remember(strings) {
                            listOf(
                                AppThemeMode.SYSTEM to strings.themeSystem,
                                AppThemeMode.LIGHT to strings.themeLight,
                                AppThemeMode.DARK to strings.themeDark
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            themeOptions.forEachIndexed { index, (mode, label) ->
                                val itemShape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size)
                                SegmentedButton(
                                    shape = itemShape,
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                    modifier = Modifier.tvFocusRing(itemShape)
                                ) {
                                    Text(label)
                                }
                            }
                        }

                        OutlinedCard(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.alpha(if (canUseAmoled) 1f else 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(RoundedCornerShape(16.dp))
                                    .clickable(enabled = canUseAmoled) {
                                        onUpdateSettings { it.copy(amoledPureBlack = !it.amoledPureBlack) }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(strings.amoledPureBlack, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Switch(
                                    checked = settings.amoledPureBlack,
                                    enabled = canUseAmoled,
                                    onCheckedChange = null
                                )
                            }
                        }

                        OutlinedCard(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(strings.themeColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    AppAccentColor.entries.forEach { accent ->
                                        val selected = settings.accentColor == accent
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .tvFocusRing(CircleShape)
                                                .clip(CircleShape)
                                                .background(accent.swatch)
                                                .then(
                                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                    else Modifier
                                                )
                                                .clickable { onUpdateSettings { it.copy(accentColor = accent) } }
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = accent.name,
                                                    tint = accent.checkOnSwatch,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Comportamento
                item(key = "behavior", contentType = "behavior_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.behavior, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column {
                                SwitchRow(
                                    label = strings.autoStart,
                                    checked = settings.autoStart,
                                    onCheckedChange = { c -> onUpdateSettings { it.copy(autoStart = c) } }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                SwitchRow(
                                    label = strings.autoReconnect,
                                    checked = settings.autoReconnect,
                                    onCheckedChange = { c -> onUpdateSettings { it.copy(autoReconnect = c) } }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                SwitchRow(
                                    label = strings.connectionNotification,
                                    checked = settings.persistentNotification,
                                    onCheckedChange = { c -> onUpdateSettings { it.copy(persistentNotification = c) } }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                SwitchRow(
                                    label = strings.autoSyncOnStart,
                                    checked = settings.autoSyncOnStart,
                                    onCheckedChange = { c -> onUpdateSettings { it.copy(autoSyncOnStart = c) } }
                                )
                            }
                        }
                    }
                }

                // DNS (Menu a tendina con Quad9, OpenDNS e IP personalizzato)
                item(key = "dns", contentType = "dropdown_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.dnsTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        var dnsMenuExpanded by remember { mutableStateOf(false) }
                        val dnsItems = remember(strings) {
                            listOf(
                                AppDnsOption.SYSTEM to strings.dnsSystem,
                                AppDnsOption.ADGUARD to strings.dnsAdguard,
                                AppDnsOption.CLOUDFLARE to strings.dnsCloudflare,
                                AppDnsOption.GOOGLE to strings.dnsGoogle,
                                AppDnsOption.QUAD9 to strings.dnsQuad9,
                                AppDnsOption.OPENDNS to strings.dnsOpenDns,
                                AppDnsOption.CUSTOM to strings.dnsCustom
                            )
                        }
                        val currentDnsLabel = dnsItems.firstOrNull { (dns, _) -> dns == settings.dnsOption }?.second ?: strings.dnsSystem

                        Surface(
                            onClick = { dnsMenuExpanded = !dnsMenuExpanded },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = currentDnsLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = if (dnsMenuExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.ArrowDropDown,
                                    contentDescription = strings.dnsTitle
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = dnsMenuExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    dnsItems.forEachIndexed { index, (option, label) ->
                                        val isSelected = option == settings.dnsOption
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(
                                                    RoundedCornerShape(
                                                        topStart = if (index == 0) 16.dp else 0.dp,
                                                        topEnd = if (index == 0) 16.dp else 0.dp,
                                                        bottomStart = if (index == dnsItems.lastIndex) 16.dp else 0.dp,
                                                        bottomEnd = if (index == dnsItems.lastIndex) 16.dp else 0.dp
                                                    )
                                                )
                                                .tvFocusRing(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                                .clickable {
                                                    onUpdateSettings { it.copy(dnsOption = option) }
                                                    dnsMenuExpanded = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (index < dnsItems.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                thickness = 0.5.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (settings.dnsOption == AppDnsOption.CUSTOM) {
                            val dnsInvalid = settings.customDns.isNotBlank() &&
                                !isValidIpv4Address(settings.customDns)
                            OutlinedTextField(
                                value = settings.customDns,
                                onValueChange = { ip -> onUpdateSettings { it.copy(customDns = ip) } },
                                placeholder = { Text(strings.dnsCustomPlaceholder) },
                                singleLine = true,
                                isError = dnsInvalid,
                                supportingText = if (dnsInvalid) {
                                    { Text(strings.vpnInvalidCustomDns) }
                                } else null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Split Tunneling (App Exclusion)
                item(key = "split_tunnel", contentType = "action_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedCard(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(RoundedCornerShape(16.dp))
                                    .clickable { showAppExclusionDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Apps,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = strings.splitTunnelTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val isExclude = settings.splitTunnelMode == SplitTunnelMode.EXCLUDE
                                    val count = settings.excludedApps.size
                                    val splitSubtitle = when {
                                        count == 0 -> if (isExclude) strings.splitTunnelNoneExclude else strings.splitTunnelNoneInclude
                                        isExclude -> if (count == 1) "1 " + strings.splitTunnelExcludedOne else count.toString() + " " + strings.splitTunnelExcludedSuffix
                                        else -> if (count == 1) "1 " + strings.splitTunnelIncludedOne else count.toString() + " " + strings.splitTunnelIncludedSuffix
                                    }
                                    Text(
                                        text = splitSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Supporta il progetto (Ko-fi con QR Code e link cliccabile)
                item(key = "support", contentType = "support_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.supportProjectTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_kofi),
                                        contentDescription = strings.donateKofi,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = strings.supportProjectSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showDonateQrDialog = true },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocalCafe,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.donateKofi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // Segnala un bug (Email precompilata senza dati sensibili + QR su TV)
                item(key = "bug_report", contentType = "support_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.bugReportTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = strings.bugReportSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showBugReportDialog = true },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BugReport,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.bugReportTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // Aggiornamenti in-app (GitHub Releases)
                item(key = "app_updates_section", contentType = "card") {
                    val context = LocalContext.current
                    val installedVersion = remember(context) { AppUpdateManager.getInstalledVersionName(context) }
                    val availableUpdate by AppUpdateManager.availableRelease.collectAsStateWithLifecycle()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.updatesTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "v$installedVersion",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedCard(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SystemUpdate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = strings.updatesSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                SwitchRow(
                                    label = strings.autoCheckUpdates,
                                    checked = settings.autoCheckUpdates,
                                    onCheckedChange = { enabled ->
                                        onUpdateSettings { it.copy(autoCheckUpdates = enabled) }
                                    }
                                )
                                val rel = availableUpdate
                                if (rel != null) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = strings.updateAvailableTitle,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = "Simple Proxy ${rel.versionName}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            forceUpdateCheck = false
                                            showUpdateDialog = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusRing(CircleShape)
                                    ) {
                                        Text(strings.updateNow)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        forceUpdateCheck = true
                                        showUpdateDialog = true
                                    },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.checkForUpdates, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDonateQrDialog) {
        DonationQrDialog(
            strings = strings,
            onDismiss = { showDonateQrDialog = false }
        )
    }

    if (showBugReportDialog) {
        BugReportDialog(
            settings = settings,
            strings = strings,
            onDismiss = { showBugReportDialog = false }
        )
    }

    if (showUpdateDialog) {
        AppUpdateDialog(
            forceFreshCheck = forceUpdateCheck,
            strings = strings,
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showAppExclusionDialog) {
        SplitTunnelAppsDialog(
            splitTunnelMode = settings.splitTunnelMode,
            excludedPackages = settings.excludedApps,
            strings = strings,
            onToggleMode = { mode -> onUpdateSettings { it.copy(splitTunnelMode = mode) } },
            onSetPackagesSelected = { packages, selected ->
                onUpdateSettings { current ->
                    val updated = current.excludedApps.toMutableSet()
                    if (selected) updated += packages else updated -= packages
                    current.copy(excludedApps = updated.toSet())
                }
            },
            onDismiss = { showAppExclusionDialog = false }
        )
    }
}

@Composable
private fun DonationQrDialog(
    strings: AppStrings,
    onDismiss: () -> Unit
) {
    val url = DonationLinks.KO_FI_URL
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.Default) {
            generateQrBitmap(url, sizePx = 512)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "${strings.donateScanQrTitle} · ${strings.donateKofi}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = url,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                val dialogContext = LocalContext.current
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .tvFocusRing(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                dialogContext.startActivity(intent)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = strings.donateScanQrHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusRing(CircleShape)
                ) {
                    Text(strings.done)
                }
            }
        }
    }
}

@Composable
private fun BugReportDialog(
    settings: AppSettings,
    strings: AppStrings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    val versionName = remember(context) {
        runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    val diagnostics = remember(versionName, settings.dnsOption, settings.splitTunnelMode, settings.excludedApps.size, settings.language) {
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        buildString {
            appendLine("App: Simple Proxy v$versionName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${if (isTv) "Android TV" else "Mobile"})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("DNS mode: ${settings.dnsOption.name}")
            appendLine("Split tunneling: ${settings.splitTunnelMode.name} (${settings.excludedApps.size} apps)")
            appendLine("Language: ${settings.language.name}")
            appendLine()
            appendLine("Descrizione problema / Issue:")
        }
    }

    val issueUrl = remember(versionName, diagnostics) {
        "${DonationLinks.BUG_REPORT_URL}?title=${Uri.encode("[Simple Proxy v$versionName] Bug Report")}&body=${Uri.encode(diagnostics)}"
    }

    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = issueUrl) {
        value = withContext(Dispatchers.Default) {
            generateQrBitmap(issueUrl, sizePx = 512)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 380.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.bugReportTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = DonationLinks.BUG_REPORT_URL,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                Text(
                    text = "github.com/carleseee/SimpleProxy/issues/new",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .tvFocusRing(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, issueUrl.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = strings.bugReportDialogHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("SimpleProxy Diagnostics", diagnostics))
                        copied = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusRing(CircleShape)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) strings.diagnosticsCopied else strings.copyDiagnostics)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusRing(CircleShape)
                ) {
                    Text(strings.done)
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int = 512): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

private val appIconCache = android.util.LruCache<String, ImageBitmap>(120)

private enum class SplitTunnelAppFilter { ALL, USER, SYSTEM }

@Immutable
private data class SplitTunnelApp(
    val primaryPackage: String,
    val packageNames: List<String>,
    val label: String,
    val isSystem: Boolean,
    val uid: Int
) {
    val uidPackageCount: Int get() = packageNames.size
}

@SuppressLint("QueryPermissionsNeeded")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SplitTunnelAppsDialog(
    splitTunnelMode: SplitTunnelMode,
    excludedPackages: Set<String>,
    strings: AppStrings,
    onToggleMode: (SplitTunnelMode) -> Unit,
    onSetPackagesSelected: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
    if (isTv) {
        SplitTunnelAppsDialogTv(
            splitTunnelMode = splitTunnelMode,
            excludedPackages = excludedPackages,
            strings = strings,
            onToggleMode = onToggleMode,
            onSetPackagesSelected = onSetPackagesSelected,
            onDismiss = onDismiss
        )
        return
    }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SplitTunnelAppFilter.ALL) }
    var apps by remember { mutableStateOf<List<SplitTunnelApp>?>(null) }
    var pendingSystemSelection by remember { mutableStateOf<List<SplitTunnelApp>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            // Android 11+ returns the visible packages only: launcher queries plus the
            // targeted system packages declared in AndroidManifest.xml <queries>.
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            val byUid = linkedMapOf<Int, MutableList<ApplicationInfo>>()
            infos.forEach { info ->
                if (info.packageName == context.packageName || !info.enabled) return@forEach
                byUid.getOrPut(info.uid) { mutableListOf() }.add(info)
            }
            byUid.map { (uid, members) ->
                val primary = members.minWithOrNull(
                    compareBy({ it.packageName != "android" }, { it.packageName.length }, { it.packageName })
                ) ?: members.first()
                val label = if (uid == 1000) {
                    strings.splitTunnelAndroidSystem
                } else {
                    runCatching { pm.getApplicationLabel(primary).toString() }
                        .getOrDefault(primary.packageName)
                }
                val isSystem = members.any { info ->
                    (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                }
                SplitTunnelApp(
                    primaryPackage = primary.packageName,
                    packageNames = members.map { it.packageName }.sorted(),
                    label = label.ifBlank { primary.packageName },
                    isSystem = isSystem,
                    uid = uid
                )
            }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        SideEffect {
            if (isTv) {
                (dialogView.parent as? DialogWindowProvider)?.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            modifier = if (isTv) {
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.94f)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .widthIn(max = 900.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .widthIn(max = 460.dp)
                    .heightIn(max = 640.dp)
            }
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 12.dp)
            ) {
                Text(
                    text = strings.splitTunnelTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                var modeMenuExpanded by remember { mutableStateOf(false) }
                val modes = remember(strings) {
                    listOf(
                        SplitTunnelMode.EXCLUDE to strings.splitTunnelModeExclude,
                        SplitTunnelMode.INCLUDE to strings.splitTunnelModeInclude
                    )
                }
                val currentModeLabel = modes.firstOrNull { (mode, _) -> mode == splitTunnelMode }?.second
                    ?: strings.splitTunnelModeExclude

                Surface(
                    onClick = { modeMenuExpanded = !modeMenuExpanded },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusRing(RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentModeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (modeMenuExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.ArrowDropDown,
                            contentDescription = strings.splitTunnelTitle
                        )
                    }
                }

                AnimatedVisibility(
                    visible = modeMenuExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { index, (mode, label) ->
                                val isSelected = mode == splitTunnelMode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = if (index == 0) 16.dp else 0.dp,
                                                topEnd = if (index == 0) 16.dp else 0.dp,
                                                bottomStart = if (index == modes.lastIndex) 16.dp else 0.dp,
                                                bottomEnd = if (index == modes.lastIndex) 16.dp else 0.dp
                                            )
                                        )
                                        .tvFocusRing(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable {
                                            onToggleMode(mode)
                                            modeMenuExpanded = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                if (index < modes.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = if (splitTunnelMode == SplitTunnelMode.INCLUDE) {
                        strings.splitTunnelHintInclude
                    } else {
                        strings.splitTunnelHintExclude
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (splitTunnelMode == SplitTunnelMode.INCLUDE && excludedPackages.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = strings.vpnIncludeNeedsSelection,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.splitTunnelSearchHint) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val loaded = apps
                if (loaded == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = isTv)
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    val query = searchQuery.trim()
                    val filtered = remember(loaded, filter, query) {
                        loaded.filter { app ->
                            val matchesFilter = when (filter) {
                                SplitTunnelAppFilter.ALL -> true
                                SplitTunnelAppFilter.USER -> !app.isSystem
                                SplitTunnelAppFilter.SYSTEM -> app.isSystem
                            }
                            matchesFilter && (query.isEmpty() ||
                                app.label.contains(query, ignoreCase = true) ||
                                app.packageNames.any { it.contains(query, ignoreCase = true) })
                        }
                    }
                    val userCount = loaded.count { !it.isSystem }
                    val systemCount = loaded.count { it.isSystem }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.ALL,
                            label = strings.splitTunnelFilterAll,
                            count = loaded.size,
                            onClick = { filter = SplitTunnelAppFilter.ALL }
                        )
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.USER,
                            label = strings.splitTunnelFilterUser,
                            count = userCount,
                            onClick = { filter = SplitTunnelAppFilter.USER }
                        )
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.SYSTEM,
                            label = strings.splitTunnelFilterSystem,
                            count = systemCount,
                            onClick = { filter = SplitTunnelAppFilter.SYSTEM }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val needsSystemConfirmation = filtered.any { app ->
                                    app.isSystem && !app.packageNames.any { it in excludedPackages }
                                }
                                if (needsSystemConfirmation) {
                                    pendingSystemSelection = filtered
                                } else {
                                    onSetPackagesSelected(
                                        filtered.map { it.primaryPackage }.toSet(),
                                        true
                                    )
                                }
                            },
                            enabled = filtered.any { app ->
                                !app.packageNames.any { it in excludedPackages }
                            }
                        ) {
                            Text(strings.splitTunnelSelectAll)
                        }
                        TextButton(
                            onClick = {
                                onSetPackagesSelected(
                                    filtered.flatMap { it.packageNames }.toSet(),
                                    false
                                )
                            },
                            enabled = filtered.any { app ->
                                app.packageNames.any { it in excludedPackages }
                            }
                        ) {
                            Text(strings.splitTunnelDeselectAll)
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.splitTunnelEmpty,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val appsListState = rememberLazyListState()
                        LazyColumn(
                            state = appsListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = isTv),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filtered, key = { it.primaryPackage }, contentType = { "app_row" }) { app ->
                                val isSelected = app.packageNames.any { it in excludedPackages }
                                val iconBitmap by produceState<ImageBitmap?>(
                                    initialValue = remember(app.primaryPackage) { appIconCache[app.primaryPackage] },
                                    key1 = app.primaryPackage
                                ) {
                                    if (value != null) return@produceState
                                    val loadedIcon = withContext(Dispatchers.IO) {
                                        runCatching {
                                            context.packageManager
                                                .getApplicationIcon(app.primaryPackage)
                                                .toBitmap(width = 96, height = 96)
                                                .asImageBitmap()
                                        }.getOrNull()
                                    }
                                    if (loadedIcon != null) {
                                        appIconCache.put(app.primaryPackage, loadedIcon)
                                        value = loadedIcon
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .tvFocusRing(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isSelected) {
                                                onSetPackagesSelected(app.packageNames.toSet(), false)
                                            } else {
                                                onSetPackagesSelected(setOf(app.primaryPackage), true)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val bmp = iconBitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Apps,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (app.isSystem) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = strings.splitTunnelFilterSystem,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val detail = if (app.uidPackageCount > 1) {
                                            "${app.primaryPackage} · " + String.format(
                                                Locale.ROOT,
                                                strings.splitTunnelUidFormat,
                                                app.uid,
                                                app.uidPackageCount
                                            )
                                        } else {
                                            app.primaryPackage
                                        }
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(
                                        checked = isSelected,
                                        onCheckedChange = null
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.tvFocusRing(CircleShape)
                    ) {
                        Text(strings.done)
                    }
                }
            }
        }
    }

    val pendingSelection = pendingSystemSelection
    if (pendingSelection != null) {
        AlertDialog(
            onDismissRequest = { pendingSystemSelection = null },
            title = { Text(strings.splitTunnelSystemConfirmTitle) },
            text = { Text(strings.splitTunnelSystemConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetPackagesSelected(
                            pendingSelection.map { it.primaryPackage }.toSet(),
                            true
                        )
                        pendingSystemSelection = null
                    }
                ) {
                    Text(strings.splitTunnelSelectAll)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSystemSelection = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SplitTunnelAppsDialogTv(
    splitTunnelMode: SplitTunnelMode,
    excludedPackages: Set<String>,
    strings: AppStrings,
    onToggleMode: (SplitTunnelMode) -> Unit,
    onSetPackagesSelected: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SplitTunnelAppFilter.ALL) }
    var apps by remember { mutableStateOf<List<SplitTunnelApp>?>(null) }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var pendingSystemSelection by remember { mutableStateOf<List<SplitTunnelApp>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            val byUid = linkedMapOf<Int, MutableList<ApplicationInfo>>()
            infos.forEach { info ->
                if (info.packageName == context.packageName || !info.enabled) return@forEach
                byUid.getOrPut(info.uid) { mutableListOf() }.add(info)
            }
            byUid.map { (uid, members) ->
                val primary = members.minWithOrNull(
                    compareBy({ it.packageName != "android" }, { it.packageName.length }, { it.packageName })
                ) ?: members.first()
                val label = if (uid == 1000) {
                    strings.splitTunnelAndroidSystem
                } else {
                    runCatching { pm.getApplicationLabel(primary).toString() }
                        .getOrDefault(primary.packageName)
                }
                val isSystem = members.any { info ->
                    (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                }
                SplitTunnelApp(
                    primaryPackage = primary.packageName,
                    packageNames = members.map { it.packageName }.sorted(),
                    label = label.ifBlank { primary.packageName },
                    isSystem = isSystem,
                    uid = uid
                )
            }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        }
    }

    val loaded = apps
    val query = searchQuery.trim()
    val filtered = remember(loaded, filter, query) {
        loaded?.filter { app ->
            val matchesFilter = when (filter) {
                SplitTunnelAppFilter.ALL -> true
                SplitTunnelAppFilter.USER -> !app.isSystem
                SplitTunnelAppFilter.SYSTEM -> app.isSystem
            }
            matchesFilter && (query.isEmpty() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageNames.any { it.contains(query, ignoreCase = true) })
        } ?: emptyList()
    }
    val userCount = loaded?.count { !it.isSystem } ?: 0
    val systemCount = loaded?.count { it.isSystem } ?: 0
    val modes = remember(strings) {
        listOf(
            SplitTunnelMode.EXCLUDE to strings.splitTunnelModeExclude,
            SplitTunnelMode.INCLUDE to strings.splitTunnelModeInclude
        )
    }
    val currentModeLabel = modes.firstOrNull { (mode, _) -> mode == splitTunnelMode }?.second
        ?: strings.splitTunnelModeExclude
    val isInclude = splitTunnelMode == SplitTunnelMode.INCLUDE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxSize(0.94f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = strings.splitTunnelTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        onClick = { modeMenuExpanded = !modeMenuExpanded },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusRing(RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentModeLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (modeMenuExpanded) {
                                    Icons.Rounded.KeyboardArrowUp
                                } else {
                                    Icons.Rounded.ArrowDropDown
                                },
                                contentDescription = strings.splitTunnelTitle
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = modeMenuExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                modes.forEachIndexed { index, (mode, label) ->
                                    val isSelected = mode == splitTunnelMode
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(
                                                RoundedCornerShape(
                                                    topStart = if (index == 0) 16.dp else 0.dp,
                                                    topEnd = if (index == 0) 16.dp else 0.dp,
                                                    bottomStart = if (index == modes.lastIndex) 16.dp else 0.dp,
                                                    bottomEnd = if (index == modes.lastIndex) 16.dp else 0.dp
                                                )
                                            )
                                            .tvFocusRing(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                onToggleMode(mode)
                                                modeMenuExpanded = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    if (index < modes.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = if (isInclude) strings.splitTunnelHintInclude else strings.splitTunnelHintExclude,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isInclude && excludedPackages.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = strings.vpnIncludeNeedsSelection,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.splitTunnelSearchHint) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.ALL,
                            label = strings.splitTunnelFilterAll,
                            count = loaded?.size ?: 0,
                            onClick = { filter = SplitTunnelAppFilter.ALL }
                        )
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.USER,
                            label = strings.splitTunnelFilterUser,
                            count = userCount,
                            onClick = { filter = SplitTunnelAppFilter.USER }
                        )
                        SplitTunnelFilterChip(
                            selected = filter == SplitTunnelAppFilter.SYSTEM,
                            label = strings.splitTunnelFilterSystem,
                            count = systemCount,
                            onClick = { filter = SplitTunnelAppFilter.SYSTEM }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.tvFocusRing(CircleShape)
                        ) {
                            Text(strings.done)
                        }
                    }
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val needsSystemConfirmation = filtered.any { app ->
                                    app.isSystem && !app.packageNames.any { it in excludedPackages }
                                }
                                if (needsSystemConfirmation) {
                                    pendingSystemSelection = filtered
                                } else {
                                    onSetPackagesSelected(
                                        filtered.map { it.primaryPackage }.toSet(),
                                        true
                                    )
                                }
                            },
                            enabled = filtered.any { app ->
                                !app.packageNames.any { it in excludedPackages }
                            }
                        ) {
                            Text(strings.splitTunnelSelectAll)
                        }
                        TextButton(
                            onClick = {
                                onSetPackagesSelected(
                                    filtered.flatMap { it.packageNames }.toSet(),
                                    false
                                )
                            },
                            enabled = filtered.any { app ->
                                app.packageNames.any { it in excludedPackages }
                            }
                        ) {
                            Text(strings.splitTunnelDeselectAll)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (loaded == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        } else if (filtered.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strings.splitTunnelEmpty,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    filtered,
                                    key = { it.primaryPackage },
                                    contentType = { "app_row" }
                                ) { app ->
                                    val isSelected = app.packageNames.any { it in excludedPackages }
                                    val iconBitmap by produceState<ImageBitmap?>(
                                        initialValue = remember(app.primaryPackage) {
                                            appIconCache[app.primaryPackage]
                                        },
                                        key1 = app.primaryPackage
                                    ) {
                                        if (value != null) return@produceState
                                        val loadedIcon = withContext(Dispatchers.IO) {
                                            runCatching {
                                                context.packageManager
                                                    .getApplicationIcon(app.primaryPackage)
                                                    .toBitmap(width = 96, height = 96)
                                                    .asImageBitmap()
                                            }.getOrNull()
                                        }
                                        if (loadedIcon != null) {
                                            appIconCache.put(app.primaryPackage, loadedIcon)
                                            value = loadedIcon
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .tvFocusRing(RoundedCornerShape(12.dp))
                                            .clickable {
                                                if (isSelected) {
                                                    onSetPackagesSelected(app.packageNames.toSet(), false)
                                                } else {
                                                    onSetPackagesSelected(setOf(app.primaryPackage), true)
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val bmp = iconBitmap
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(9.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Apps,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = app.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (app.isSystem) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer
                                                    ) {
                                                        Text(
                                                            text = strings.splitTunnelFilterSystem,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            val detail = if (app.uidPackageCount > 1) {
                                                "${app.primaryPackage} · " + String.format(
                                                    Locale.ROOT,
                                                    strings.splitTunnelUidFormat,
                                                    app.uid,
                                                    app.uidPackageCount
                                                )
                                            } else {
                                                app.primaryPackage
                                            }
                                            Text(
                                                text = detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isSelected,
                                            onCheckedChange = null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val pendingSelection = pendingSystemSelection
    if (pendingSelection != null) {
        AlertDialog(
            onDismissRequest = { pendingSystemSelection = null },
            title = { Text(strings.splitTunnelSystemConfirmTitle) },
            text = { Text(strings.splitTunnelSystemConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetPackagesSelected(
                            pendingSelection.map { it.primaryPackage }.toSet(),
                            true
                        )
                        pendingSystemSelection = null
                    }
                ) {
                    Text(strings.splitTunnelSelectAll)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSystemSelection = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun SplitTunnelFilterChip(
    selected: Boolean,
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.tvFocusRing(CircleShape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private enum class UpdateStep {
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    NEEDS_PERMISSION,
    COMPLETED,
    DELETED,
    ERROR
}

@Composable
private fun AppUpdateDialog(
    forceFreshCheck: Boolean,
    strings: AppStrings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedVersion = remember(context) { AppUpdateManager.getInstalledVersionName(context) }
    val cachedAvailable by AppUpdateManager.availableRelease.collectAsStateWithLifecycle()
    val useCachedInitial = (!forceFreshCheck) && (cachedAvailable != null)

    var step by remember {
        mutableStateOf(if (useCachedInitial) UpdateStep.AVAILABLE else UpdateStep.CHECKING)
    }
    var release by remember { mutableStateOf(if (useCachedInitial) cachedAvailable else null) }
    var downloadedApk by remember { mutableStateOf<DownloadedApk?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var installAfterDownload by remember { mutableStateOf(true) }
    var installerLaunched by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var deleteAfterProcess by remember {
        mutableStateOf(AppUpdateManager.isAutoDeleteAfterProcessEnabled(context))
    }

    fun startCheck() {
        step = UpdateStep.CHECKING
        errorMessage = null
        scope.launch {
            when (val res = AppUpdateManager.fetchLatestRelease(installedVersion)) {
                is UpdateCheckResult.UpToDate -> {
                    release = null
                    step = UpdateStep.UP_TO_DATE
                }
                is UpdateCheckResult.Available -> {
                    release = res.release
                    step = UpdateStep.AVAILABLE
                }
                is UpdateCheckResult.Error -> {
                    errorMessage = strings.updateCheckError
                    step = UpdateStep.ERROR
                }
            }
        }
    }

    fun startDownload(installNow: Boolean) {
        val target = release ?: return
        installAfterDownload = installNow
        installerLaunched = false
        downloadProgress = 0
        step = UpdateStep.DOWNLOADING
        scope.launch {
            runCatching {
                AppUpdateManager.downloadApk(
                    context = context,
                    release = target,
                    // Keep a visible copy in Downloads when the user asked to keep the APK,
                    // even if they chose "Download and install".
                    saveToDownloads = !installNow || !deleteAfterProcess,
                    onProgress = { pct -> downloadProgress = pct }
                )
            }.onSuccess { downloaded ->
                downloadedApk = downloaded
                if (installNow) {
                    if (AppUpdateManager.canInstallPackages(context)) {
                        installerLaunched = AppUpdateManager.launchApkInstaller(context, downloaded)
                        if (installerLaunched) {
                            step = UpdateStep.COMPLETED
                        } else {
                            errorMessage = strings.updateCheckError
                            step = UpdateStep.ERROR
                        }
                    } else {
                        step = UpdateStep.NEEDS_PERMISSION
                    }
                } else {
                    step = UpdateStep.COMPLETED
                }
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: strings.updateCheckError
                step = UpdateStep.ERROR
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, step, downloadedApk, installerLaunched) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val apk = downloadedApk
                if ((step == UpdateStep.NEEDS_PERMISSION) && (apk != null) && AppUpdateManager.canInstallPackages(context)) {
                    installerLaunched = AppUpdateManager.launchApkInstaller(context, apk)
                    step = UpdateStep.COMPLETED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (forceFreshCheck || (cachedAvailable == null)) {
            startCheck()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.updatesTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${strings.currentVersionLabel}: v$installedVersion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (step) {
                    UpdateStep.CHECKING -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 22.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = strings.checkingUpdates,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    UpdateStep.UP_TO_DATE, UpdateStep.ERROR -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = if (step == UpdateStep.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (step == UpdateStep.ERROR) (errorMessage ?: strings.updateCheckError) else "${strings.upToDateMessage} (v$installedVersion)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(CircleShape)
                        ) {
                            Text(strings.done)
                        }
                    }

                    UpdateStep.AVAILABLE -> {
                        val rel = release
                        if (rel != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = strings.updateAvailableTitle,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Simple Proxy ${rel.versionName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        if (rel.assetSizeBytes > 0L) {
                                            Text(
                                                text = AppUpdateManager.formatSizeMb(rel.assetSizeBytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (rel.releaseNotes.isNotBlank()) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        Text(
                                            text = rel.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Surface(
                                onClick = {
                                    val next = !deleteAfterProcess
                                    deleteAfterProcess = next
                                    AppUpdateManager.setAutoDeleteAfterProcessEnabled(context, next)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(RoundedCornerShape(14.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.deleteFileAfterProcess,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                    )
                                    Switch(checked = deleteAfterProcess, onCheckedChange = null)
                                }
                            }

                            if (!rel.apkUrl.isNullOrBlank()) {
                                Button(
                                    onClick = { startDownload(installNow = true) },
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.downloadAndInstall)
                                }
                                OutlinedButton(
                                    onClick = { startDownload(installNow = false) },
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Text(strings.downloadOnly)
                                }
                            } else {
                                Text(
                                    text = strings.noApkAssetMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, rel.htmlUrl.toUri()).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(CircleShape)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.openReleasePage)
                                }
                            }

                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(CircleShape)
                            ) {
                                Text(strings.cancel)
                            }
                        }
                    }

                    UpdateStep.DOWNLOADING -> {
                        val rel = release
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(strings.downloadingUpdate, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text("$downloadProgress%", style = MaterialTheme.typography.labelMedium)
                                }
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                rel?.let {
                                    Text(
                                        text = "SimpleProxy-v${it.versionName}.apk",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    UpdateStep.NEEDS_PERMISSION -> {
                        Text(
                            text = strings.installPermissionNeeded,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { AppUpdateManager.openInstallPermissionSettings(context) },
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(CircleShape)
                        ) {
                            Text(strings.openInstallSettings)
                        }
                        OutlinedButton(
                            onClick = {
                                val apk = downloadedApk
                                if ((apk != null) && AppUpdateManager.canInstallPackages(context)) {
                                    installerLaunched = AppUpdateManager.launchApkInstaller(context, apk)
                                    step = UpdateStep.COMPLETED
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(CircleShape)
                        ) {
                            Text(strings.installNow)
                        }
                    }

                    UpdateStep.COMPLETED -> {
                        val apk = downloadedApk
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text(strings.downloadComplete, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    apk?.let {
                                        Text(
                                            text = "${it.displayName} (${AppUpdateManager.formatSizeMb(it.sizeBytes)})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (it.mediaStoreUri != null) {
                                            Text(
                                                text = strings.savedToDownloads,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!installAfterDownload && apk != null) {
                            Button(
                                onClick = {
                                    if (AppUpdateManager.canInstallPackages(context)) {
                                        installerLaunched = AppUpdateManager.launchApkInstaller(context, apk)
                                    } else {
                                        step = UpdateStep.NEEDS_PERMISSION
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(CircleShape)
                            ) {
                                Text(strings.installNow)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    apk?.let { AppUpdateManager.deleteDownloadedApk(context, it) }
                                    downloadedApk = null
                                    step = UpdateStep.DELETED
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(CircleShape)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.deleteApkNow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(CircleShape)
                            ) {
                                Text(strings.keepApkFile, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    UpdateStep.DELETED -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(strings.apkDeletedMessage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusRing(CircleShape)
                        ) {
                            Text(strings.done)
                        }
                    }
                }
            }
        }
    }
}

private fun languageFlag(language: AppLanguage): String = when (language) {
    AppLanguage.IT -> "🇮🇹"
    AppLanguage.EN -> "🇬🇧"
    AppLanguage.FR -> "🇫🇷"
    AppLanguage.DE -> "🇩🇪"
    AppLanguage.ES -> "🇪🇸"
    AppLanguage.RU -> "🇷🇺"
    AppLanguage.ZH -> "🇨🇳"
    AppLanguage.JA -> "🇯🇵"
    AppLanguage.PL -> "🇵🇱"
}
