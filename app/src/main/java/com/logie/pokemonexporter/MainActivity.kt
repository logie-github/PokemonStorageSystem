package com.logie.packageexporter

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderState(
    val access: AccessState = AccessState.Connecting,
    val saves: List<ReadSave> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val scanning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = ShizukuConnection(application)
    private val mutable = MutableStateFlow(ReaderState())
    val state = mutable.asStateFlow()
    init {
        connection.start()
        viewModelScope.launch { connection.state.collect { access ->
            mutable.update { it.copy(access = access) }
            if (access == AccessState.Ready) scan()
        } }
    }
    fun permission() = connection.requestPermission()
    fun retry() = connection.refresh()
    fun scan() = viewModelScope.launch {
        val fs = connection.files ?: return@launch
        if (mutable.value.scanning) return@launch
        mutable.update { it.copy(scanning = true) }
        try {
            val (saves, diagnostics) = withContext(Dispatchers.IO) { SaveReader(fs).scan() }
            mutable.update { it.copy(saves = saves, diagnostics = diagnostics, scanning = false) }
        } catch (e: Exception) { mutable.update { it.copy(scanning = false, diagnostics = listOf(e.message ?: "Scan failed")) } }
    }
    override fun onCleared() { connection.stop(); super.onCleared() }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderApp(model) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReaderApp(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ReadSave?>(null) }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Pokémon Save Reader") }, actions = {
            if (selected != null) TextButton({ selected = null }) { Text("Library") }
            else TextButton({ model.scan() }, enabled = state.access == AccessState.Ready && !state.scanning) { Text("Refresh") }
        }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Gen 1 Recomp • Gen2Recomped · Read-only") }
                if (state.access != AccessState.Ready) item {
                    Text(when (val access = state.access) {
                        AccessState.Connecting -> "Connecting to Shizuku…"
                        AccessState.Missing -> "Install and start Shizuku to read game saves."
                        AccessState.Stopped -> "Start Shizuku, then retry."
                        AccessState.PermissionNeeded -> "Allow Shizuku access to read your game saves."
                        is AccessState.Failed -> access.reason
                        else -> ""
                    })
                    if (state.access == AccessState.PermissionNeeded) Button({ model.permission() }) { Text("Grant access") }
                    else Button({ model.retry() }) { Text("Retry") }
                } else if (selected == null) {
                    if (state.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.saves.forEach { save -> item {
                        ElevatedCard(onClick = { selected = save }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(save.data?.child("player")?.get("name")?.toString() ?: "Unreadable save", style = MaterialTheme.typography.titleLarge)
                                Text(save.label)
                                Text(save.error ?: "${save.data?.child("party")?.size ?: 0} party Pokémon")
                                Text("Browse save →")
                            }
                        }
                    } }
                    if (!state.scanning && state.saves.isEmpty()) item { Text("No saves found. Save in the game first, then refresh. Directory checks:") }
                    state.diagnostics.forEach { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
                } else {
                    val save = selected!!
                    item { Text(save.label); Text(save.path, style = MaterialTheme.typography.bodySmall) }
                    if (save.error != null) item { Text(save.error) }
                    val data = save.data
                    if (data != null) {
                        item {
                            Text(data.child("player")["name"]?.toString() ?: "Trainer", style = MaterialTheme.typography.headlineMedium)
                            Text("Trainer ID: ${data.child("player")["id"] ?: "Unknown"}")
                            Text("Party", style = MaterialTheme.typography.titleLarge)
                        }
                        data.child("party").numbered().forEach { (_, mon) -> item { PokemonCard(mon) } }
                        val boxes = if (data["boxes"] is Map<*, *>) data.child("boxes").numbered()
                            else if (data["box"] is Map<*, *>) listOf(1 to data.child("box")) else emptyList()
                        boxes.forEach { (index, box) ->
                            item { Text(data.child("boxNames")[index.toDouble()]?.toString() ?: "Box $index", style = MaterialTheme.typography.titleLarge) }
                            if (box.isEmpty()) item { Text("Empty") }
                            box.numbered().forEach { (_, mon) -> item { PokemonCard(mon) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PokemonCard(mon: Map<Any, Any?>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val species = mon["species"]?.toString() ?: "Unknown species"
            Text(mon["nickname"]?.toString() ?: species, style = MaterialTheme.typography.titleMedium)
            Text("$species · Level ${(mon["level"] as? Number)?.toInt() ?: "?"}")
            Text("HP: ${mon["hp"] ?: mon["currentHp"] ?: "?"} / ${mon["maxHp"] ?: "?"}")
            Text("Moves: " + mon.child("moves").entries.sortedBy { (it.key as? Number)?.toInt() ?: 0 }.joinToString { (_, move) ->
                if (move is Map<*, *>) (move["id"] ?: move["move"] ?: "Unknown").toString() else move.toString()
            })
        }
    }
}
