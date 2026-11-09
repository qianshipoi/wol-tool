package com.qianshi.woltool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    val motionScheme = MaterialTheme.motionScheme

    AnimatedContent(
        targetState = editorId,
        // Motion comes from the Material 3 Expressive motion scheme instead of hand-tuned specs.
        transitionSpec = {
            if (targetState != null) {
                // List → editor: the editor springs in from the right while the list recedes.
                (slideInHorizontally(motionScheme.defaultSpatialSpec()) { width -> width / 3 } +
                    fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(motionScheme.fastSpatialSpec()) { width -> -width / 10 } +
                        fadeOut(motionScheme.fastEffectsSpec()) +
                        scaleOut(motionScheme.fastSpatialSpec(), targetScale = 0.94f))
            } else {
                // Editor → list: the editor slides away to the right and springs back out of view.
                (slideInHorizontally(motionScheme.fastSpatialSpec()) { width -> -width / 10 } +
                    fadeIn(motionScheme.fastEffectsSpec()) +
                    scaleIn(motionScheme.fastSpatialSpec(), initialScale = 0.94f)) togetherWith
                    (slideOutHorizontally(motionScheme.defaultSpatialSpec()) { width -> width / 3 } +
                        fadeOut(motionScheme.defaultEffectsSpec()))
            }
        },
        label = "page",
    ) { targetId ->
        if (targetId != null) {
            DeviceEditorScreen(
                initial = devices.firstOrNull { it.id == targetId },
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
    }

    if (showHelp) {
        WakeHelpDialog(onDismiss = { showHelp = false })
    }
}
