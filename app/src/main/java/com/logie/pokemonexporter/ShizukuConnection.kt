package com.logie.packageexporter

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed interface AccessState {
    data object Connecting : AccessState
    data object Missing : AccessState
    data object Stopped : AccessState
    data object PermissionNeeded : AccessState
    data object Ready : AccessState
    data class Failed(val reason: String) : AccessState
}

class ShizukuConnection(private val context: Context) {
    private val mutable = MutableStateFlow<AccessState>(AccessState.Connecting)
    val state = mutable.asStateFlow()
    var files: PackageFileSystem? = null
        private set

    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST) {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else mutable.value = AccessState.Failed("Shizuku permission denied")
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { files = null; mutable.value = AccessState.Stopped }
    private val service = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) mutable.value = AccessState.Failed("Shizuku returned no service")
            else { files = PackageFileSystem(binder); mutable.value = AccessState.Ready }
        }
        override fun onServiceDisconnected(name: ComponentName?) { files = null; mutable.value = AccessState.Stopped }
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
    fun requestPermission() = Shizuku.requestPermission(REQUEST)
    fun refresh() {
        if (runCatching { context.packageManager.getPackageInfo(SHIZUKU, 0) }.isFailure) { mutable.value = AccessState.Missing; return }
        if (!Shizuku.pingBinder()) { mutable.value = AccessState.Stopped; return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect()
        else mutable.value = AccessState.PermissionNeeded
    }
    private fun connect() {
        if (files != null) { mutable.value = AccessState.Ready; return }
        mutable.value = AccessState.Connecting
        val args = Shizuku.UserServiceArgs(ComponentName(context, PackageFileService::class.java))
            .daemon(false).processNameSuffix("export").debuggable(BuildConfig.DEBUG).version(1)
        runCatching { Shizuku.bindUserService(args, service) }
            .onFailure { mutable.value = AccessState.Failed(it.message ?: "Could not bind Shizuku service") }
    }

    companion object { const val REQUEST = 70; const val SHIZUKU = "moe.shizuku.privileged.api" }
}
