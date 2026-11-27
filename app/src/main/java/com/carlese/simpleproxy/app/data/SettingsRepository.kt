package com.carlese.simpleproxy.app.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.carlese.simpleproxy.app.ui.theme.AppAccentColor
import com.carlese.simpleproxy.app.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage {
    IT, EN, FR, DE, ES, RU, ZH, JA, PL;

    companion object {
        fun fromLocale(locale: Locale = Locale.getDefault()): AppLanguage =
            when (locale.language.lowercase()) {
                "it" -> IT
                "en" -> EN
                "fr" -> FR
                "de" -> DE
                "es" -> ES
                "ru" -> RU
                "zh" -> ZH
                "ja" -> JA
                "pl" -> PL
                else -> EN
            }
    }
}

enum class SplitTunnelMode { EXCLUDE, INCLUDE }

@Immutable
data class AppSettings(
    val language: AppLanguage = AppLanguage.fromLocale(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val accentColor: AppAccentColor = AppAccentColor.BLUE,
    val amoledPureBlack: Boolean = false,
    val dnsOption: AppDnsOption = AppDnsOption.SYSTEM,
    val customDns: String = "",
    val autoStart: Boolean = false,
    val autoReconnect: Boolean = true,
    val persistentNotification: Boolean = true,
    val autoSyncOnStart: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.EXCLUDE,
    val excludedApps: Set<String> = emptySet(),
    val activeProfileId: String? = null,
    val lastSyncTime: String? = null
)

class SettingsRepository private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("simpleproxy_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val defaultLang = AppLanguage.fromLocale()
        val lang = prefs.getString("language", null)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: defaultLang
        val theme = prefs.getString("theme_mode", null)
            ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.SYSTEM
        val accent = prefs.getString("accent_color", null)
            ?.let { runCatching { AppAccentColor.valueOf(it) }.getOrNull() } ?: AppAccentColor.BLUE
        val dns = prefs.getString("dns_option", null)
            ?.let { runCatching { AppDnsOption.valueOf(it) }.getOrNull() } ?: AppDnsOption.SYSTEM
        val splitMode = prefs.getString("split_tunnel_mode", null)
            ?.let { runCatching { SplitTunnelMode.valueOf(it) }.getOrNull() } ?: SplitTunnelMode.EXCLUDE
        val excluded = prefs.getStringSet("excluded_apps", null)?.toSet() ?: emptySet()

        return AppSettings(
            language = lang,
            themeMode = theme,
            accentColor = accent,
            amoledPureBlack = prefs.getBoolean("amoled_pure_black", false),
            dnsOption = dns,
            customDns = prefs.getString("custom_dns", "") ?: "",
            autoStart = prefs.getBoolean("auto_start", false),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            persistentNotification = prefs.getBoolean("persistent_notification", true),
            autoSyncOnStart = prefs.getBoolean("auto_sync_on_start", true),
            autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
            splitTunnelMode = splitMode,
            excludedApps = excluded,
            activeProfileId = prefs.getString("active_profile_id", null),
            lastSyncTime = prefs.getString("last_sync_time", null)
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit {
            putString("language", s.language.name)
            putString("theme_mode", s.themeMode.name)
            putString("accent_color", s.accentColor.name)
            putBoolean("amoled_pure_black", s.amoledPureBlack)
            putString("dns_option", s.dnsOption.name)
            putString("custom_dns", s.customDns)
            putBoolean("auto_start", s.autoStart)
            putBoolean("auto_reconnect", s.autoReconnect)
            putBoolean("persistent_notification", s.persistentNotification)
            putBoolean("auto_sync_on_start", s.autoSyncOnStart)
            putBoolean("auto_check_updates", s.autoCheckUpdates)
            putString("split_tunnel_mode", s.splitTunnelMode.name)
            putStringSet("excluded_apps", HashSet(s.excludedApps))
            putString("active_profile_id", s.activeProfileId)
            putString("last_sync_time", s.lastSyncTime)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
