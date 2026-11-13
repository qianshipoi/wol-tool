package com.qianshi.woltool

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianshi.woltool.ui.ColorSplashTransition
import com.qianshi.woltool.ui.DeviceEditorScreen
import com.qianshi.woltool.ui.DeviceListScreen
import com.qianshi.woltool.ui.RemoteScreen
import com.qianshi.woltool.ui.WakeHelpDialog
import com.qianshi.woltool.ui.theme.WolToolTheme
import kotlinx.coroutines.CancellationException

private sealed interface PendingRoute {
    data class Editor(val id: String) : PendingRoute
    data object Remote : PendingRoute
}

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
    var showRemote by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val overlayVisible = editorId != null || showRemote
    var backProgress by remember { mutableFloatStateOf(0f) }

    var splashVisible by remember { mutableStateOf(false) }
    var splashOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var pendingRoute by remember { mutableStateOf<PendingRoute?>(null) }
    var pageEntryProgress by remember { mutableFloatStateOf(1f) }
    var entryRequestToken by remember { mutableIntStateOf(0) }
    var pendingDefaultEntry by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun openWithSplash(route: PendingRoute, origin: Offset) {
        pendingDefaultEntry = false
        pageEntryProgress = 1f
        pendingRoute = route
        splashOrigin = origin
        splashVisible = true
    }

    fun openDefault() {
        // Start the slide only after the destination has been composed once.
        // Otherwise a slow first composition can finish the animation while
        // the page is still blank, which looks like a flash.
        pendingDefaultEntry = true
        entryRequestToken++
        pageEntryProgress = 0f
    }

    fun closeOverlay() {
        pendingDefaultEntry = false
        pageEntryProgress = 1f
        editorId = null
        showRemote = false
    }

    LaunchedEffect(overlayVisible, entryRequestToken) {
        if (overlayVisible && pendingDefaultEntry) {
            pendingDefaultEntry = false
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 320,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                pageEntryProgress = value
            }
        }
    }

    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) backProgress = 0f
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PredictiveBackHandler(enabled = overlayVisible) { progress ->
            try {
                progress.collect { event ->
                    backProgress = event.progress
                }
                closeOverlay()
            } catch (cancellation: CancellationException) {
                backProgress = 0f
                throw cancellation
            }
        }
    } else {
        // Android 12 and below have no predictive-back progress. Keep the normal
        // back behavior; the overlay is simply removed without a preview.
        BackHandler(enabled = overlayVisible) {
            closeOverlay()
        }
    }

    val pageEntry = pageEntryProgress

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the list composed underneath for predictive back and for the
        // color splash. It stays fully visible during the default slide-in, so
        // a slow first composition cannot produce a blank/gray flash.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (overlayVisible) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            DeviceListScreen(
                devices = devices,
                wakingIds = wakingIds,
                snackbarHostState = snackbarHostState,
                onWake = viewModel::wake,
                onAdd = { bounds ->
                    openWithSplash(
                        route = PendingRoute.Editor(""),
                        origin = bounds?.center ?: Offset.Unspecified,
                    )
                },
                onEdit = { device ->
                    openDefault()
                    editorId = device.id
                },
                onDelete = viewModel::delete,
                onOpenRemote = {
                    openDefault()
                    showRemote = true
                },
                onShowHelp = { showHelp = true },
            )
        }

        if (overlayVisible) {
            // A scrim dims the list only while the user holds a predictive-back
            // gesture.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.18f * backProgress }
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { change -> change.consume() }
                            }
                        }
                    },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The add flow uses the color splash; other entries use
                        // a standard opaque page slide from the right. The page
                        // is already fully opaque while it is off-screen, so a
                        // slow first layout never flashes a blank page.
                        translationX = (1f - pageEntry) * size.width
                        val entryScale = 0.98f + 0.02f * pageEntry
                        val backScale = 1f - 0.16f * backProgress
                        val scale = entryScale * backScale
                        scaleX = scale
                        scaleY = scale
                        shape = RoundedCornerShape(24.dp * backProgress)
                        clip = true
                        shadowElevation = 24.dp.toPx() * backProgress
                    },
            ) {
                if (showRemote) {
                    RemoteScreen(
                        devices = devices,
                        onBack = ::closeOverlay,
                    )
                } else {
                    DeviceEditorScreen(
                        initial = devices.firstOrNull { it.id == editorId },
                        onBack = ::closeOverlay,
                        onSave = { device ->
                            viewModel.save(device)
                            closeOverlay()
                        },
                        onDelete = { device ->
                            viewModel.delete(device)
                            closeOverlay()
                        },
                        handleSystemBack = false,
                    )
                }
            }
        }

        if (splashVisible) {
            ColorSplashTransition(
                origin = splashOrigin,
                color = MaterialTheme.colorScheme.primary,
                onMidpoint = {
                    when (val route = pendingRoute) {
                        is PendingRoute.Editor -> editorId = route.id
                        PendingRoute.Remote -> showRemote = true
                        null -> Unit
                    }
                },
                onFinished = {
                    splashVisible = false
                    pendingRoute = null
                },
            )
        }
    }

    if (showHelp) {
        WakeHelpDialog(onDismiss = { showHelp = false })
    }
}
