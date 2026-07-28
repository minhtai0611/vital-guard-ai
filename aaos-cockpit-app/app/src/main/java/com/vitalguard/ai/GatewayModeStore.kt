package com.vitalguard.ai

import android.content.Context

enum class GatewayMode { FAKE, REAL }

interface GatewayModeStore {
    fun get(): GatewayMode
    fun set(mode: GatewayMode)
}

class InMemoryGatewayModeStore(initial: GatewayMode = GatewayMode.FAKE) : GatewayModeStore {
    @Volatile private var current: GatewayMode = initial
    override fun get(): GatewayMode = current
    override fun set(mode: GatewayMode) {
        current = mode
    }
}

/** Production store: persists across process restarts via SharedPreferences,
 * so a live GATEWAY_MODE=REAL demo survives an app/service restart. */
class PrefsGatewayModeStore(private val context: Context) : GatewayModeStore {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): GatewayMode {
        val raw = prefs.getString(KEY_MODE, GatewayMode.FAKE.name) ?: GatewayMode.FAKE.name
        return runCatching { GatewayMode.valueOf(raw) }.getOrDefault(GatewayMode.FAKE)
    }

    override fun set(mode: GatewayMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "vital_guard_gateway_mode"
        private const val KEY_MODE = "mode"
    }
}
