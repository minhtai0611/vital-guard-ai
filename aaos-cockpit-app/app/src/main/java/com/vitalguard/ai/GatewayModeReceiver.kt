package com.vitalguard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GatewayModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_GATEWAY_MODE) return
        val requested = intent.getStringExtra(EXTRA_MODE) ?: return
        val mode = runCatching { GatewayMode.valueOf(requested) }.getOrNull() ?: run {
            Log.w("VitalGuardGatewayMode", "Ignoring invalid GATEWAY_MODE value: $requested")
            return
        }
        PrefsGatewayModeStore(context).set(mode)
        Log.w("VitalGuardGatewayMode", "GATEWAY_MODE switched to $mode")
    }

    companion object {
        const val ACTION_SET_GATEWAY_MODE = "com.vitalguard.ai.SET_GATEWAY_MODE"
        const val EXTRA_MODE = "mode"
    }
}
