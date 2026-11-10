package com.qianshi.woltool

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianshi.woltool.ui.DeviceEditorScreen
import com.qianshi.woltool.ui.DeviceListScreen
import com.qianshi.woltool.ui.RemoteScreen
import com.qianshi.woltool.ui.WakeHelpDialog
import com.qianshi.woltool.ui.theme.WolToolTheme
import kotlinx.coroutines.CancellationException

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
    var revealBounds by remember { mutableStateOf<Rect?>(null) }
    val maskProgress = remember { Animatable(0f) }
    val maskAlpha = remember { Animatable(1f) }
    var maskVisible by remember { mutableStateOf(false) }

    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            maskVisible = true
            maskProgress.snapTo(0f)
            maskAlpha.snapTo(1f)
            maskProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 320,
                    easing = FastOutSlowInEasing,
                ),
            )
            maskAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = FastOutSlowInEasing,
                ),
            )
            maskVisible = false
        } else {
            maskVisible = false
            maskProgress.snapTo(0f)
            maskAlpha.snapTo(1f)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun closeOverlay() {
        editorId = null
        showRemote = false
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the list composed underneath so the predictive-back gesture has a
        // real previous page to reveal. It grows from 0.92 to full size as the
        // foreground page shrinks.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The list stays full size underneath. The foreground page
                    // shrinks during the gesture, revealing this page in the
                    // margins around it.
                    scaleX = 1f
                    scaleY = 1f
                }
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
                    revealBounds = bounds
                    editorId = ""
                },
                onEdit = { device ->
                    revealBounds = null
                    editorId = device.id
                },
                onOpenRemote = {
                    revealBounds = null
                    showRemote = true
                },
                onShowHelp = { showHelp = true },
            )
        }

        if (overlayVisible) {
            // A scrim both dims the list and consumes input so the background
            // screen cannot be scrolled or clicked while a page is open.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.18f * backProgress
                    }
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

            val pageVisible = maskProgress.value >= 0.999f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The page appears only after the colored mask has
                        // covered the screen. Predictive back then shrinks it.
                        alpha = if (pageVisible) 1f else 0f
                        val scale = 1f - 0.16f * backProgress
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

            if (maskVisible) {
                val maskColor = MaterialTheme.colorScheme.primary
                val maskProgressValue = maskProgress.value
                val maskAlphaValue = maskAlpha.value
                val currentRevealBounds = revealBounds
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = maskAlphaValue }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes
                                        .forEach { change -> change.consume() }
                                }
                            }
                        },
                ) {
                    val center = currentRevealBounds?.center
                        ?: Offset(size.width / 2f, size.height / 2f)
                    val startRadius = currentRevealBounds?.let {
                        min(it.width, it.height) / 2f
                    } ?: 0f
                    val maxRadius = hypot(
                        maxOf(center.x, size.width - center.x),
                        maxOf(center.y, size.height - center.y),
                    ) + 2.dp.toPx()
                    val radius = startRadius +
                        (maxRadius - startRadius) * maskProgressValue
                    drawCircle(
                        color = maskColor,
                        radius = radius,
                        center = center,
                    )
                }
            }
        }
    }

    if (showHelp) {
        WakeHelpDialog(onDismiss = { showHelp = false })
    }
}
