package com.logie.gen1storage

import android.content.SharedPreferences
import com.logie.gen1storage.ui.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two folder paths a restore has to bring back on its own: which folder
 * BACKUP FOLDER pushes into, and which folder ROMS was last pointed at.
 *
 * Both are plain strings in the same preferences file every other setting
 * lives in, so `BackupExport` already carries them without knowing either
 * one by name; this is what proves the two properties themselves round trip
 * the way any other setting here does.
 */
class AppSettingsFolderTest {

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

    @Test
    fun `roms folder path round trips independently of the backup folder path`() {
        val settings = AppSettings(FakePrefs())
        assertNull("nothing picked yet", settings.romsFolderUri)
        assertNull("nothing picked yet", settings.backupFolderUri)

        settings.romsFolderUri = "content://com.android.externalstorage.documents/tree/roms"
        settings.backupFolderUri = "content://com.android.externalstorage.documents/tree/backups"

        assertEquals("content://com.android.externalstorage.documents/tree/roms", settings.romsFolderUri)
        assertEquals("content://com.android.externalstorage.documents/tree/backups", settings.backupFolderUri)
    }

    @Test
    fun `a folder can be repointed without disturbing the other one`() {
        val settings = AppSettings(FakePrefs())
        settings.romsFolderUri = "content://tree/old-roms"
        settings.backupFolderUri = "content://tree/backups"

        settings.romsFolderUri = "content://tree/new-roms"

        assertEquals("content://tree/new-roms", settings.romsFolderUri)
        assertEquals("content://tree/backups", settings.backupFolderUri)
    }
}
