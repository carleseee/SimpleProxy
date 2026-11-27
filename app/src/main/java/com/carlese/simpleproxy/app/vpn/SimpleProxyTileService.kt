package com.carlese.simpleproxy.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.carlese.simpleproxy.app.MainActivity
import com.carlese.simpleproxy.app.data.ProxyDatabase
import com.carlese.simpleproxy.app.data.SettingsRepository
import com.carlese.simpleproxy.app.ui.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimpleProxyTileService : TileService() {

    private var tileScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        tileScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tileScope = scope

        scope.launch {
            combine(
                SimpleProxyVpnService.isRunning,
                SimpleProxyVpnService.isConnecting,
                SettingsRepository.getInstance(applicationContext).settings
            ) { running, connecting, settings ->
                Triple(running, connecting, settings)
            }.collect { (running, connecting, settings) ->
                val profiles = withContext(Dispatchers.IO) {
                    ProxyDatabase.getInstance(applicationContext).proxyDao().getAllOnce()
                }
                val activeProfile = profiles.find { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
                val strings = stringsFor(settings.language)
                val tile = qsTile ?: return@collect

                tile.state = when {
                    running || connecting -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = when {
                        connecting -> strings.connecting
                        activeProfile != null -> activeProfile.name
                        else -> strings.qsTileSubtitleNoProfile
                    }
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (SimpleProxyVpnService.isRunning.value || SimpleProxyVpnService.isConnecting.value) {
            SimpleProxyVpnService.stop(applicationContext)
            return
        }

        val scope = tileScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val settings = SettingsRepository.getInstance(applicationContext).settings.value
            val profiles = withContext(Dispatchers.IO) {
                ProxyDatabase.getInstance(applicationContext).proxyDao().getAllOnce()
            }
            val activeProfile = profiles.find { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
            if (activeProfile != null && VpnService.prepare(applicationContext) == null) {
                SimpleProxyVpnService.start(applicationContext, activeProfile.id)
            } else {
                openMainActivity()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
