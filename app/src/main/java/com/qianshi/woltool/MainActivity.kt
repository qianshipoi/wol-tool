package com.qianshi.woltool

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianshi.woltool.ui.DeviceEditorScreen
import com.qianshi.woltool.ui.DeviceListScreen
import com.qianshi.woltool.ui.RemoteScreen
import com.qianshi.woltool.ui.WakeHelpDialog
import com.qianshi.woltool.ui.theme.WolToolTheme
import kotlin.math.roundToInt
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
    var revealLabel by remember { mutableStateOf("添加设备") }
    var revealVisible by remember { mutableStateOf(false) }
    val revealProgress = remember { Animatable(0f) }

    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            revealVisible = true
            revealProgress.snapTo(0f)
            revealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 420,
                    easing = FastOutSlowInEasing,
                ),
            )
            revealVisible = false
        } else {
            revealVisible = false
            revealProgress.snapTo(0f)
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
        // real previous page to reveal.
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
                onAdd = { bounds, label ->
                    revealBounds = bounds
                    revealLabel = label
                    editorId = ""
                },
                onEdit = { device ->
                    revealBounds = null
                    revealLabel = ""
                    editorId = device.id
                },
                onOpenRemote = {
                    revealBounds = null
                    revealLabel = ""
                    showRemote = true
                },
                onShowHelp = { showHelp = true },
            )
        }

        if (overlayVisible) {
            // A scrim dims the list while the user holds a predictive-back
            // gesture. It also blocks input to the background screen.
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

            val revealValue = revealProgress.value
            val pageAlpha = smoothstep(0.42f, 0.96f, revealValue)
            val labelAlpha = (1f - revealValue / 0.28f).coerceIn(0f, 1f)
            val morphColor = lerp(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.background,
                smoothstep(0.30f, 0.92f, revealValue),
            )
            val currentRevealBounds = revealBounds

            if (revealVisible) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val defaultSize = 56.dp.toPx()
                    val start = currentRevealBounds ?: Rect(
                        left = size.width / 2f - defaultSize / 2f,
                        top = size.height / 2f - defaultSize / 2f,
                        right = size.width / 2f + defaultSize / 2f,
                        bottom = size.height / 2f + defaultSize / 2f,
                    )
                    val end = Rect(0f, 0f, size.width, size.height)
                    val left = start.left + (end.left - start.left) * revealValue
                    val top = start.top + (end.top - start.top) * revealValue
                    val right = start.right + (end.right - start.right) * revealValue
                    val bottom = start.bottom + (end.bottom - start.bottom) * revealValue
                    val corner = (start.height / 2f) * (1f - revealValue)
                    drawRoundRect(
                        color = morphColor,
                        topLeft = Offset(left, top),
                        size = Size(
                            width = (right - left).coerceAtLeast(0f),
                            height = (bottom - top).coerceAtLeast(0f),
                        ),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The page fades in as the shared container morphs into
                        // the full-screen surface. Predictive back shrinks it.
                        alpha = pageAlpha
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

            if (revealVisible) {
                // Block page input while the shared container is animating.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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

                if (currentRevealBounds != null && labelAlpha > 0f && revealLabel.isNotBlank()) {
                    val density = LocalDensity.current
                    val width = with(density) { currentRevealBounds.width.toDp() }
                    val height = with(density) { currentRevealBounds.height.toDp() }
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    currentRevealBounds.left.roundToInt(),
                                    currentRevealBounds.top.roundToInt(),
                                )
                            }
                            .size(width = width, height = height)
                            .graphicsLayer { alpha = labelAlpha },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = revealLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHelp) {
        WakeHelpDialog(onDismiss = { showHelp = false })
    }
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val progress = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}
