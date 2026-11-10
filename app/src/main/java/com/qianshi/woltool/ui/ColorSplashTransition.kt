package com.qianshi.woltool.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Same structure as Androidify's ColorSplashTransitionScreen:
 * expand a solid color circle from the tapped button, switch screens while
 * the circle covers the window, then shrink the circle to reveal the new page.
 */
@Composable
fun ColorSplashTransition(
    origin: Offset,
    color: Color,
    onMidpoint: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val center = if (origin == Offset.Unspecified) {
            Offset(width / 2f, height / 2f)
        } else {
            origin
        }
        val extra = with(LocalDensity.current) { 8.dp.toPx() }
        val targetRadius = hypot(
            maxOf(center.x, width - center.x),
            maxOf(center.y, height - center.y),
        ) + extra
        val radius = remember { Animatable(0f) }

        LaunchedEffect(targetRadius) {
            radius.animateTo(
                targetValue = targetRadius,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
            )
            onMidpoint()
            radius.animateTo(
                targetValue = 0f,
                animationSpec = tween(380, easing = FastOutSlowInEasing),
            )
            onFinished()
        }

        Canvas(
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
        ) {
            drawCircle(
                color = color,
                radius = radius.value,
                center = center,
            )
        }
    }
}
