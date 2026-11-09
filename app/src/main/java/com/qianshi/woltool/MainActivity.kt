package com.qianshi.woltool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianshi.woltool.ui.DeviceEditorScreen
import com.qianshi.woltool.ui.DeviceListScreen
import com.qianshi.woltool.ui.WakeHelpDialog
import com.qianshi.woltool.ui.theme.WolToolTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WolToolTheme {
                WolApp()
            }
        }
    }
}

@Composable
private fun WolApp(viewModel: WolViewModel = viewModel()) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val wakingIds by viewModel.wakingIds.collectAsStateWithLifecycle()

    // null = list, "" = new device, otherwise the id being edited.
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val editingDevice = devices.firstOrNull { it.id == editorId }

    if (editorId != null) {
        DeviceEditorScreen(
            initial = editingDevice,
            onBack = { editorId = null },
            onSave = { device ->
                viewModel.save(device)
                editorId = null
            },
            onDelete = { device ->
                viewModel.delete(device)
                editorId = null
            },
        )
    } else {
        DeviceListScreen(
            devices = devices,
            wakingIds = wakingIds,
            snackbarHostState = snackbarHostState,
            onWake = viewModel::wake,
            onAdd = { editorId = "" },
            onEdit = { device -> editorId = device.id },
            onShowHelp = { showHelp = true },
        )
    }

    if (showHelp) {
        WakeHelpDialog(onDismiss = { showHelp = false })
    }
}
