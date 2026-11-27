package com.carlese.simpleproxy.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.carlese.simpleproxy.app.data.AppUpdateManager
import com.carlese.simpleproxy.app.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppUpdateManager.cleanupPendingUpdateIfRequested(context)
        }

        val settings = SettingsRepository.getInstance(context).settings.value
        val activeId = settings.activeProfileId
        if (!settings.autoStart || activeId.isNullOrBlank()) return

        // Avvia solo se il permesso VPN di sistema era già stato concesso dall'utente
        if (VpnService.prepare(context) == null) {
            SimpleProxyVpnService.start(context, activeId)
        }
    }
}
