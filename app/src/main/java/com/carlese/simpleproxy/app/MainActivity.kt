package com.carlese.simpleproxy.app

import android.Manifest

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.carlese.simpleproxy.app.data.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlese.simpleproxy.app.data.ProxyProfile
import com.carlese.simpleproxy.app.ui.*
import com.carlese.simpleproxy.app.ui.theme.SimpleProxyTheme
import com.google.firebase.crashlytics.FirebaseCrashlytics

class MainActivity : AppCompatActivity() {
    private val openUpdatesFromNotif = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AppUpdateManager.EXTRA_OPEN_UPDATES, false)) {
            openUpdatesFromNotif.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openUpdatesFromNotif.value = intent?.getBooleanExtra(AppUpdateManager.EXTRA_OPEN_UPDATES, false) == true
        lifecycleScope.launch(Dispatchers.IO) {
            AppUpdateManager.cleanupPendingUpdateIfRequested(applicationContext)
        }
        setContent {
            val vm: SimpleProxyViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val isRunning by vm.isVpnRunning.collectAsStateWithLifecycle()
            val isConnecting by vm.isVpnConnecting.collectAsStateWithLifecycle()
            val connectedProfileId by vm.connectedProfileId.collectAsStateWithLifecycle()
            val vmVpnError by vm.vpnError.collectAsStateWithLifecycle()
            val accountState by vm.accountState.collectAsStateWithLifecycle()
            val strings = remember(settings.language) { stringsFor(settings.language) }

            LaunchedEffect(settings.dnsOption, settings.excludedApps.size, settings.themeMode) {
                runCatching {
                    val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("is_tv", isTv)
                        setCustomKey("dns_mode", settings.dnsOption.name)
                        setCustomKey("excluded_apps", settings.excludedApps.size)
                        setCustomKey("theme_mode", settings.themeMode.name)
                    }
                }
            }

            var showOptions by remember { mutableStateOf(false) }
            val pendingOpenUpdates by openUpdatesFromNotif

            LaunchedEffect(pendingOpenUpdates) {
                if (pendingOpenUpdates) {
                    showOptions = true
                }
            }

            LaunchedEffect(settings.autoCheckUpdates, strings.updateAvailableTitle) {
                if (settings.autoCheckUpdates) {
                    AppUpdateManager.checkAndNotifyIfAvailable(
                        context = applicationContext,
                        title = strings.updateAvailableTitle,
                        channelName = strings.updateNotificationChannel,
                    )
                }
            }
            var showEditor by remember { mutableStateOf(false) }
            var showAccountDialog by remember { mutableStateOf(false) }
            var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
            var localError by remember { mutableStateOf<String?>(null) }
            var askedNotificationsThisSession by remember { mutableStateOf(false) }

            val legacySignInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                if (result.resultCode != RESULT_CANCELED) {
                    vm.completeLegacyGoogleSignIn(result.data)
                }
            }

            val driveConsentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                if ((result.resultCode == RESULT_OK) && (result.data != null)) {
                    vm.completeDriveConsent(result.data)
                } else {
                    vm.onDriveConsentCancelled()
                }
            }

            LaunchedEffect(accountState.pendingDriveConsent) {
                val pending = accountState.pendingDriveConsent ?: return@LaunchedEffect
                vm.consumePendingDriveConsent()
                runCatching {
                    driveConsentLauncher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build(),
                    )
                }.onFailure { err ->
                    vm.reportSyncError(err.localizedMessage ?: "Google Drive consent error")
                }
            }

            val vpnPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    vm.startVpn()
                } else {
                    localError = strings.vpnPermissionRequired
                }
            }

            fun launchVpnWithSystemConsent() {
                val prepareIntent = VpnService.prepare(this@MainActivity)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    vm.startVpn()
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                launchVpnWithSystemConsent()
            }

            fun handleToggleVpn() {
                if (isRunning || isConnecting) {
                    vm.stopVpn()
                    return
                }
                if (settings.activeProfileId == null) {
                    localError = strings.vpnNoActiveProfile
                    return
                }
                if (
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) &&
                    settings.persistentNotification &&
                    (!askedNotificationsThisSession) &&
                    (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    askedNotificationsThisSession = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launchVpnWithSystemConsent()
                }
            }

            BackHandler(enabled = showOptions) {
                showOptions = false
            }

            SimpleProxyTheme(
                themeMode = settings.themeMode,
                accentColor = settings.accentColor,
                amoledPureBlack = settings.amoledPureBlack
            ) {
                val googleInitial = remember(accountState.email, accountState.displayName) {
                    (
                        accountState.email?.trim()?.takeIf { it.isNotEmpty() }
                            ?: accountState.displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "G"
                        ).first().uppercase()
                }
                AnimatedContent(
                    targetState = showOptions,
                    transitionSpec = {
                        if (targetState) {
                            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 })
                                .togetherWith(fadeOut(tween(140)))
                        } else {
                            fadeIn(tween(180))
                                .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 8 })
                        }
                    },
                    label = "screenTransition"
                ) { optionsVisible ->
                    if (optionsVisible) {
                        OptionsScreen(
                            settings = settings,
                            accountState = accountState,
                            strings = strings,
                            onSignInGoogle = {
                                vm.signInWithGoogle(this@MainActivity) { intentSender ->
                                    runCatching {
                                        legacySignInLauncher.launch(
                                            IntentSenderRequest.Builder(intentSender).build(),
                                        )
                                    }.onFailure { err ->
                                        vm.reportSyncError(
                                            err.localizedMessage ?: "Google Sign-In failed",
                                        )
                                    }
                                }
                            },
                            onSyncNow = vm::syncNow,
                            onSignOutGoogle = vm::signOutGoogle,
                            onDismissAccountError = vm::clearAccountError,
                            onUpdateSettings = vm::updateSettings,
                            onBack = { showOptions = false },
                            openUpdatesOnLaunch = pendingOpenUpdates,
                            onConsumeOpenUpdates = { openUpdatesFromNotif.value = false },
                        )
                    } else {
                        MainScreen(
                            profiles = profiles,
                            activeProfileId = settings.activeProfileId,
                            connectedProfileId = connectedProfileId,
                            isVpnRunning = isRunning,
                            isVpnConnecting = isConnecting,
                            vpnError = localError ?: vmVpnError,
                            isGoogleConnected = accountState.uid != null,
                            googlePhotoUrl = accountState.photoUrl,
                            googleInitial = googleInitial,
                            strings = strings,
                            onToggleVpn = ::handleToggleVpn,
                            onDismissVpnError = {
                                localError = null
                                vm.clearVpnError()
                            },
                            onSelectProfile = vm::selectProfile,
                            onEditProfile = { profile ->
                                editingProfile = profile
                                showEditor = true
                            },
                            onAddProfileClick = {
                                editingProfile = null
                                showEditor = true
                            },
                            onGoogleAccountClick = { showAccountDialog = true },
                            onOpenOptions = { showOptions = true }
                        )
                    }
                }

                if (showAccountDialog && (accountState.uid != null)) {
                    GoogleAccountDialog(
                        accountState = accountState,
                        lastSyncTime = settings.lastSyncTime,
                        strings = strings,
                        onSyncNow = vm::syncNow,
                        onSignOut = {
                            vm.signOutGoogle()
                            showAccountDialog = false
                        },
                        onDismiss = { showAccountDialog = false }
                    )
                }

                if (showEditor) {
                    ProxyEditorDialog(
                        initialProfile = editingProfile,
                        strings = strings,
                        onDismiss = { showEditor = false },
                        onSave = vm::saveProfile,
                        onDelete = vm::deleteProfile
                    )
                }
            }
        }
    }
}
