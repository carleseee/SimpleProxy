package com.carlese.simpleproxy.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopVpnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SimpleProxyVpnService.ACTION_STOP) {
            SimpleProxyVpnService.stop(context)
        }
    }
}
