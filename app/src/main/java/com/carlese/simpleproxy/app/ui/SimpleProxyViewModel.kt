package com.carlese.simpleproxy.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlese.simpleproxy.app.data.*
import com.carlese.simpleproxy.app.vpn.SimpleProxyVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SimpleProxyViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ProxyDatabase.getInstance(application).proxyDao()
    private val settingsRepo = SettingsRepository.getInstance(application)
    private val cloudSync = CloudSyncRepository(application, dao, settingsRepo)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val accountState: StateFlow<CloudAccountState> = cloudSync.accountState

    val profiles: StateFlow<List<ProxyProfile>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isVpnRunning: StateFlow<Boolean> = SimpleProxyVpnService.isRunning
    val isVpnConnecting: StateFlow<Boolean> = SimpleProxyVpnService.isConnecting
    val connectedProfileId: StateFlow<String?> = SimpleProxyVpnService.connectedProfileId
    val vpnError: StateFlow<String?> = SimpleProxyVpnService.errorMessage

    fun startVpn() {
        val activeId = settings.value.activeProfileId ?: return
        SimpleProxyVpnService.start(getApplication(), activeId)
    }

    fun stopVpn() {
        SimpleProxyVpnService.stop(getApplication())
    }

    fun clearVpnError() {
        SimpleProxyVpnService.clearError()
    }

    fun selectProfile(id: String) {
        settingsRepo.update { it.copy(activeProfileId = id) }
    }

    fun saveProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            val stamped = profile.copy(updatedAt = System.currentTimeMillis())
            dao.upsert(stamped)
            cloudSync.pushProfile(stamped)
            if (settings.value.activeProfileId == null) {
                settingsRepo.update { it.copy(activeProfileId = stamped.id) }
            } else if ((isVpnRunning.value || isVpnConnecting.value) && connectedProfileId.value == stamped.id) {
                SimpleProxyVpnService.start(getApplication(), stamped.id)
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            dao.deleteById(id)
            cloudSync.pushDelete(id)
            if ((isVpnRunning.value || isVpnConnecting.value) && connectedProfileId.value == id) {
                SimpleProxyVpnService.stop(getApplication())
            }
            if (settings.value.activeProfileId == id) {
                val remaining = dao.getAllOnce().firstOrNull { it.id != id }?.id
                settingsRepo.update { it.copy(activeProfileId = remaining) }
            }
        }
    }

    fun signInWithGoogle(
        activity: Activity,
        onNeedLegacyIntent: (IntentSender) -> Unit,
        onNeedClassicIntent: (Intent) -> Unit,
    ) {
        viewModelScope.launch {
            cloudSync.setSigningIn(true)
            when (val outcome = GoogleSignInHelper.requestGoogleIdToken(activity)) {
                is GoogleSignInOutcome.Success -> {
                    cloudSync.signInWithGoogleIdToken(outcome.idToken)
                }
                is GoogleSignInOutcome.NeedLegacyIntent -> {
                    cloudSync.setSigningIn(false)
                    onNeedLegacyIntent(outcome.intentSender)
                }
                is GoogleSignInOutcome.NeedClassicIntent -> {
                    cloudSync.setSigningIn(false)
                    onNeedClassicIntent(outcome.intent)
                }
                GoogleSignInOutcome.Cancelled -> {
                    cloudSync.setSigningIn(false)
                }
                is GoogleSignInOutcome.Error -> {
                    cloudSync.reportError(outcome.message)
                }
            }
        }
    }

    fun completeClassicGoogleSignIn(data: Intent?) {
        viewModelScope.launch {
            val result = GoogleSignInHelper.extractIdTokenFromClassicSignInIntent(data)
            result.onSuccess { idToken ->
                cloudSync.signInWithGoogleIdToken(idToken)
            }.onFailure { err ->
                cloudSync.reportError(err.localizedMessage ?: "Google Sign-In failed")
            }
        }
    }

    fun completeLegacyGoogleSignIn(data: Intent?) {
        viewModelScope.launch {
            val result = GoogleSignInHelper.extractIdTokenFromLegacyIntent(getApplication(), data)
            result.onSuccess { idToken ->
                cloudSync.signInWithGoogleIdToken(idToken)
            }.onFailure { err ->
                cloudSync.reportError(err.localizedMessage ?: "Google Sign-In failed")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            cloudSync.syncNow()
        }
    }

    fun completeDriveConsent(data: Intent?) {
        viewModelScope.launch {
            cloudSync.completeDriveConsent(data)
        }
    }

    fun consumePendingDriveConsent() {
        cloudSync.consumePendingDriveConsent()
    }

    fun onDriveConsentCancelled() {
        cloudSync.onDriveConsentCancelled()
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            cloudSync.signOut()
        }
    }

    fun clearAccountError() {
        cloudSync.clearError()
    }

    fun reportSyncError(message: String) {
        cloudSync.reportError(message)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = settings.value
        settingsRepo.update(transform)
        val current = settings.value

        val vpnOptionChanged =
            previous.dnsOption != current.dnsOption ||
                previous.customDns != current.customDns ||
                previous.autoReconnect != current.autoReconnect ||
                previous.persistentNotification != current.persistentNotification ||
                previous.splitTunnelMode != current.splitTunnelMode ||
                previous.excludedApps != current.excludedApps

        val liveProfileId = connectedProfileId.value ?: current.activeProfileId
        if (vpnOptionChanged && (isVpnRunning.value || isVpnConnecting.value) && liveProfileId != null) {
            val isReadyDns = current.dnsOption != AppDnsOption.CUSTOM ||
                isValidIpv4Address(current.customDns)
            if (isReadyDns) {
                SimpleProxyVpnService.start(getApplication(), liveProfileId)
            }
        }
    }

    override fun onCleared() {
        cloudSync.stop()
    }
}
