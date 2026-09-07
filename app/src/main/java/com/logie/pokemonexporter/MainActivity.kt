package com.logie.packageexporter

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PackageItem(val packageName: String, val path: String)
data class ScreenState(
    val access: AccessState = AccessState.Connecting,
    val packages: List<PackageItem> = emptyList(),
    val scanning: Boolean = false,
    val exporting: String? = null,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = ShizukuConnection(application)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()

    init {
        connection.start()
        viewModelScope.launch {
            connection.state.collect { access ->
                mutable.update { it.copy(access = access) }
                if (access == AccessState.Ready) scan()
            }
        }
    }

    fun requestPermission() = connection.requestPermission()
    fun retry() = connection.refresh()
    fun scan() = viewModelScope.launch {
        val fs = connection.files ?: return@launch
        mutable.update { it.copy(scanning = true, message = null) }
        runCatching {
            fs.list(PackageFileService.DATA_ROOT)
                .filter { it.directory }
                .map { PackageItem(it.name, it.path) }
                .sortedBy { it.packageName.lowercase() }
        }.onSuccess { packages -> mutable.update { it.copy(packages = packages, scanning = false, message = "Found ${packages.size} package directories") } }
            .onFailure { error -> mutable.update { it.copy(scanning = false, message = "Scan failed: ${error.message}") } }
    }

    fun export(item: PackageItem, destination: Uri) = viewModelScope.launch {
        val fs = connection.files ?: return@launch
        mutable.update { it.copy(exporting = item.packageName, message = "Exporting ${item.packageName}…") }
        runCatching {
            var count = 0
            getApplication<Application>().contentResolver.openOutputStream(destination, "w")?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    val visited = mutableSetOf<String>()
                    suspend fun walk(source: String, archive: String, depth: Int) {
                        require(depth <= 64) { "Directory nesting exceeds 64 levels" }
                        if (!visited.add(source)) return
                        val entries = fs.list(source)
                        if (entries.isEmpty()) {
                            zip.putNextEntry(ZipEntry("$archive/")); zip.closeEntry()
                        }
                        for (entry in entries) {
                            if (entry.name.isBlank() || entry.name == "." || entry.name == "..") continue
                            val target = "$archive/${entry.name}"
                            if (entry.directory) walk(entry.path, target, depth + 1)
                            else {
                                zip.putNextEntry(ZipEntry(target))
                                fs.copy(entry, zip)
                                zip.closeEntry()
                                count++
                            }
                        }
                    }
                    walk(item.path, item.packageName, 0)
                }
            } ?: error("Could not create destination file")
            count
        }.onSuccess { count -> mutable.update { it.copy(exporting = null, message = "Exported $count files from ${item.packageName}") } }
            .onFailure { error -> mutable.update { it.copy(exporting = null, message = "Export failed: ${error.message}") } }
    }

    override fun onCleared() { connection.stop(); super.onCleared() }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PackageExporterApp(viewModel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PackageExporterApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PackageItem?>(null) }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val item = pending
        pending = null
        if (uri != null && item != null) viewModel.export(item, uri)
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = MaterialTheme.colorScheme.error)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("PACKAGE EXPORTER") }, actions = {
                IconButton(viewModel::scan, enabled = state.access == AccessState.Ready && !state.scanning) { Icon(Icons.Default.Refresh, "Rescan") }
            })
        }) { padding ->
            when (val access = state.access) {
                AccessState.Ready -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Android/data packages", style = MaterialTheme.typography.headlineSmall)
                        Text("Exports every file Shizuku can read while preserving folders.")
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    items(state.packages, key = { it.packageName }) { item ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(item.packageName, style = MaterialTheme.typography.titleMedium)
                                Text(item.path, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = { pending = item; createZip.launch("${item.packageName}.zip") },
                                    enabled = state.exporting == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (state.exporting == item.packageName) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Archive, null)
                                    Text(if (state.exporting == item.packageName) "  EXPORTING…" else "  EXPORT EVERY FILE (.ZIP)")
                                }
                            }
                        }
                    }
                }
                else -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(when (access) {
                            AccessState.Connecting -> "Connecting to Shizuku…"
                            AccessState.Missing -> "Install Shizuku first."
                            AccessState.Stopped -> "Start Shizuku, then retry."
                            AccessState.PermissionNeeded -> "Allow Shizuku access to list and export package files."
                            is AccessState.Failed -> access.reason
                            AccessState.Ready -> "Ready"
                        })
                        if (access == AccessState.PermissionNeeded) Button(viewModel::requestPermission) { Text("GRANT ACCESS") }
                        else if (access != AccessState.Connecting) Button(viewModel::retry) { Text("RETRY") }
                    }
                }
            }
        }
    }
}
