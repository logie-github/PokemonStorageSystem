package com.logie.gen1storage.saveaccess

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.logie.gen1storage.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Where the Shizuku route currently stands, as the UI needs to explain it. */
sealed interface AccessState {
    data object Connecting : AccessState
    data object Missing : AccessState
    data object Stopped : AccessState
    data object PermissionNeeded : AccessState
    data object Ready : AccessState
    data class Failed(val reason: String) : AccessState
}

/**
 * Binds [ShizukuFileService] through Shizuku and exposes the resulting
 * privileged volume.
 *
 * Shizuku is the route that needs no folder picking at all; when it is absent
 * the app falls back to a Storage Access Framework tree the player grants once.
 */
class ShizukuConnection(private val context: Context) {

    private val mutable = MutableStateFlow<AccessState>(AccessState.Connecting)
    val state = mutable.asStateFlow()

    var volume: ShizukuVolume? = null
        private set

    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST) {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else mutable.value = AccessState.Failed("Shizuku permission denied")
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        volume = null
        mutable.value = AccessState.Stopped
    }
    private val service = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) mutable.value = AccessState.Failed("Shizuku returned no service")
            else {
                volume = ShizukuVolume(binder)
                mutable.value = AccessState.Ready
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            volume = null
            mutable.value = AccessState.Stopped
        }
    }

    fun start() {
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        refresh()
    }

    fun stop() {
        Shizuku.removeRequestPermissionResultListener(permission)
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
    }

    fun requestPermission() = runCatching { Shizuku.requestPermission(REQUEST) }.getOrElse {
        mutable.value = AccessState.Failed(it.message ?: "Could not ask Shizuku for permission")
    }

    fun refresh() {
        if (runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isFailure) {
            mutable.value = AccessState.Missing
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            mutable.value = AccessState.Stopped
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() == PackageManager.PERMISSION_GRANTED) {
            connect()
        } else {
            mutable.value = AccessState.PermissionNeeded
        }
    }

    private fun connect() {
        if (volume != null) { mutable.value = AccessState.Ready; return }
        mutable.value = AccessState.Connecting
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuFileService::class.java))
            .daemon(false)
            .processNameSuffix("saves")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        runCatching { Shizuku.bindUserService(args, service) }
            .onFailure { mutable.value = AccessState.Failed(it.message ?: "Could not bind Shizuku service") }
    }

    companion object {
        const val REQUEST = 70
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
