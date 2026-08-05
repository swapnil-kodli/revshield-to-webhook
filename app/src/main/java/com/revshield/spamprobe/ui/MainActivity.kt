package com.revshield.spamprobe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.revshield.spamprobe.presentation.theme.RevShieldTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Hoisted to the Activity so the lifecycle callbacks below can refresh it. The default factory
    // handles AndroidViewModel (Application-arg) subclasses.
    private val vm: ProbeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Request ANSWER_PHONE_CALLS so TelecomManager.endCall() can auto-reject after capture.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), 1)
        }
        setContent { RevShieldTheme { App(vm) } }
    }

    // The accessibility service is toggled OUTSIDE this app (in system Settings). Re-read the real
    // state every time we return to the foreground, so "Capture service: ON/OFF" never lies about a
    // service that is actually connected. onResume covers the return trip from the Settings screen.
    override fun onResume() {
        super.onResume()
        vm.refreshAccessibility()
    }

    // Also refresh when the window regains focus — covers overlays (e.g. the permission dialog) that
    // return focus WITHOUT a full onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) vm.refreshAccessibility()
    }
}

private enum class Tab(val label: String) { HOME("Home"), RECORDS("Records"), SETTINGS("Settings") }

@Composable
private fun App(vm: ProbeViewModel) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var exportPayload by remember { mutableStateOf<String?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val text = exportPayload
        exportPayload = null
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }
    fun export(pendingOnly: Boolean) {
        scope.launch {
            exportPayload = vm.exportText(pendingOnly)
            createDoc.launch("observations-${if (pendingOnly) "pending" else "all"}.ndjson")
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Text(t.label.take(1)) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            Tab.HOME -> HomeScreen(vm, m) { export(it) }
            Tab.RECORDS -> RecordsScreen(vm, m)
            Tab.SETTINGS -> SettingsScreen(vm, m)
        }
    }
}
