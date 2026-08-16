package com.local.spacedcards.data.lan

import android.content.Context

class LanSettingsStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LanConnectionSettings = LanConnectionSettings(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        port = prefs.getInt(KEY_PORT, DEFAULT_LAN_PORT),
        code = prefs.getString(KEY_CODE, "").orEmpty(),
    )

    fun save(
        host: String,
        port: Int,
        code: String,
    ) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port.coerceIn(1, 65535))
            .putString(KEY_CODE, code.trim())
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "lan_quiz_settings"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_CODE = "pairing_code"
    }
}
