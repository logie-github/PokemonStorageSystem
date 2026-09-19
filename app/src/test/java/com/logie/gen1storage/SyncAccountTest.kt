package com.logie.gen1storage

import android.content.SharedPreferences
import com.logie.gen1storage.sync.LinkResult
import com.logie.gen1storage.sync.SyncAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codes a device linked with, kept beside the token they produced.
 *
 * `StorageViewModel.runSync` is what actually spends them, retrying a link
 * that stopped being honoured before it tells a player there is typing left
 * to do, but that retry needs `SyncAccount` to have actually kept the codes
 * to try, and to keep them through everything a token's own lifecycle does:
 * a normal relink, a token-only refresh, an explicit unlink. This is what
 * proves that half without needing a device or a server to run.
 */
class SyncAccountTest {

    /** Just enough of the interface for one key file, in memory. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getAll() = values.toMap()
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun contains(key: String) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    private class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { removed += prefs.values.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removed.forEach { prefs.values.remove(it) }
            prefs.values.putAll(pending)
        }
    }

    private fun account() = SyncAccount(FakePrefs())

    private fun linkResult() = LinkResult(account = "acct-1", deviceToken = "tok-1", deviceId = "dev-1")

    @Test
    fun `linking keeps the codes beside the token they produced`() {
        val account = account()
        account.save(linkResult(), "PIXEL 8", "quiet-forest", "dawn-heron")

        assertTrue(account.isLinked)
        assertEquals("quiet-forest", account.code1)
        assertEquals("dawn-heron", account.code2)
        assertEquals("acct-1" to "tok-1", account.credentials())
    }

    @Test
    fun `a token-only refresh leaves the codes exactly as they were`() {
        val account = account()
        account.save(linkResult(), "PIXEL 8", "quiet-forest", "dawn-heron")

        val refreshed = LinkResult(account = "acct-1", deviceToken = "tok-2", deviceId = "dev-1")
        account.saveToken(refreshed, "PIXEL 8")

        assertEquals("tok-2", account.token)
        assertEquals("quiet-forest", account.code1)
        assertEquals("dawn-heron", account.code2)
    }

    @Test
    fun `an explicit unlink clears the codes along with everything else`() {
        val account = account()
        account.save(linkResult(), "PIXEL 8", "quiet-forest", "dawn-heron")

        account.clear()

        assertFalse(account.isLinked)
        assertNull(account.code1)
        assertNull(account.code2)
        assertNull(account.credentials())
    }

    @Test
    fun `relinking with a fresh pair replaces the old one, not both at once`() {
        val account = account()
        account.save(linkResult(), "PIXEL 8", "quiet-forest", "dawn-heron")
        account.save(linkResult(), "PIXEL 8", "bright-river", "still-owl")

        assertEquals("bright-river", account.code1)
        assertEquals("still-owl", account.code2)
    }

    @Test
    fun `a device that has never linked has no codes to try`() {
        val account = account()
        assertNull(account.code1)
        assertNull(account.code2)
        assertFalse(account.isLinked)
    }
}
