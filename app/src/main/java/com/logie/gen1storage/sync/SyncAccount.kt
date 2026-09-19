package com.logie.gen1storage.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * The linked account's credentials.
 *
 * The account id and device token together are exactly as powerful as the two
 * sync codes: they read and write every save on the account. They live in
 * app-private preferences, are never written to a log, and are excluded from
 * the debug report by construction, nothing here is ever formatted into one.
 *
 * The two codes themselves are kept alongside the token they produced, which
 * is new: they used to be typed once and forgotten the moment linking
 * succeeded. A restored backup can bring back a token the server no longer
 * honours, the account since relinked from a different phone, say, and a
 * player who just trusted a backup to put things back is not somebody this
 * app should hand a typing exercise to before it. See
 * `StorageViewModel.runSync`'s own retry, which is what actually spends
 * them; kept here because the codes are exactly as sensitive as the token
 * already living in this same file, so keeping them costs nothing a token
 * did not already cost.
 */
class SyncAccount(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("gen1storage-sync", Context.MODE_PRIVATE))

    val isLinked: Boolean get() = account != null && token != null

    val account: String? get() = prefs.getString(KEY_ACCOUNT, null)?.takeIf { it.isNotEmpty() }
    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
    val deviceId: String? get() = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }
    val deviceLabel: String? get() = prefs.getString(KEY_DEVICE_LABEL, null)

    /** What was typed in on the SAVE SYNC screen to get this link, if this app is the one that asked for it. */
    val code1: String? get() = prefs.getString(KEY_CODE1, null)?.takeIf { it.isNotEmpty() }
    val code2: String? get() = prefs.getString(KEY_CODE2, null)?.takeIf { it.isNotEmpty() }

    /** The pair the API client asks for on every authenticated call. */
    fun credentials(): Pair<String, String>? {
        val account = account ?: return null
        val token = token ?: return null
        return account to token
    }

    fun save(result: LinkResult, deviceLabel: String, code1: String, code2: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT, result.account)
            .putString(KEY_TOKEN, result.deviceToken)
            .putString(KEY_DEVICE_ID, result.deviceId)
            .putString(KEY_DEVICE_LABEL, deviceLabel)
            .putString(KEY_CODE1, code1)
            .putString(KEY_CODE2, code2)
            .apply()
    }

    /**
     * The token alone, replaced without touching the codes that got it.
     *
     * What `runSync`'s own retry calls once those codes prove they still
     * work: the pairing has not changed, only the token this device was
     * carrying for it, so the codes that just proved themselves are worth
     * keeping exactly as they were.
     */
    fun saveToken(result: LinkResult, deviceLabel: String) {
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
            .remove(KEY_CODE1)
            .remove(KEY_CODE2)
            .apply()
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_TOKEN = "device-token"
        const val KEY_DEVICE_ID = "device-id"
        const val KEY_DEVICE_LABEL = "device-label"
        const val KEY_CODE1 = "code1"
        const val KEY_CODE2 = "code2"
    }
}
