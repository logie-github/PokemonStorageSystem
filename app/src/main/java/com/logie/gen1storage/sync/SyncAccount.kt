package com.logie.gen1storage.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * The linked account's credentials.
 *
 * The account id and device token together are exactly as powerful as the two
 * sync codes: they read and write every save on the account. They live in
 * app-private preferences, are never written to a log, and are excluded from
 * the debug report by construction — nothing here is ever formatted into one.
 */
class SyncAccount(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("gen1storage-sync", Context.MODE_PRIVATE))

    val isLinked: Boolean get() = account != null && token != null

    val account: String? get() = prefs.getString(KEY_ACCOUNT, null)?.takeIf { it.isNotEmpty() }
    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
    val deviceId: String? get() = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }
    val deviceLabel: String? get() = prefs.getString(KEY_DEVICE_LABEL, null)

    /** The pair the API client asks for on every authenticated call. */
    fun credentials(): Pair<String, String>? {
        val account = account ?: return null
        val token = token ?: return null
        return account to token
    }

    fun save(result: LinkResult, deviceLabel: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT, result.account)
            .putString(KEY_TOKEN, result.deviceToken)
            .putString(KEY_DEVICE_ID, result.deviceId)
            .putString(KEY_DEVICE_LABEL, deviceLabel)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCOUNT)
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_LABEL)
            .apply()
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_TOKEN = "device-token"
        const val KEY_DEVICE_ID = "device-id"
        const val KEY_DEVICE_LABEL = "device-label"
    }
}
