package com.qianshi.woltool.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qianshi.woltool.remote.QrCodeGenerator
import java.util.Arrays

/** 二维码四周的静区宽度（单位：模块）。 */
private const val QUIET_ZONE_MODULES = 2

/** 未拿到确切宽度时使用的默认边长。 */
private val DEFAULT_SIDE_DP = 240.dp

private const val MIN_PIXELS = 96
private const val MAX_PIXELS = 1024

/**
 * 把文本渲染成可扫描的二维码。
 *
 * 位图按整数像素模块绘制（每个模块是整数个像素见方），并预留 2 个模块的静区，
 * 绘制时用 [FilterQuality.None] 保证边缘锐利，不经过缩放采样。
 * 内容为空或过长时显示一句提示文字，不会崩溃。
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    darkColor: Color = MaterialTheme.colorScheme.onSurface,
    lightColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
) {
    BoxWithConstraints(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val side = when {
            constraints.hasBoundedWidth -> maxWidth
            constraints.hasBoundedHeight -> maxHeight
            else -> DEFAULT_SIDE_DP
        }
        val sidePixels = with(density) { side.roundToPx() }.coerceIn(MIN_PIXELS, MAX_PIXELS)
        val dark = darkColor.toArgb()
        val light = lightColor.toArgb()
        val bitmap = remember(content, sidePixels, dark, light) {
            renderQrBitmap(content = content, sidePixels = sidePixels, dark = dark, light = light)
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "中继二维码",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        } else {
            Text(
                text = "二维码生成失败，请稍后重试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 生成二维码位图：先取模块矩阵，再按整数倍像素铺开。
 *
 * @return 生成失败（内容为空、过长或尺寸非法）时返回 null。
 */
private fun renderQrBitmap(
    content: String,
    sidePixels: Int,
    dark: Int,
    light: Int,
): Bitmap? {
    val matrix = QrCodeGenerator.encode(content) ?: return null
    val totalModules = matrix.size + QUIET_ZONE_MODULES * 2
    if (totalModules <= 0 || sidePixels <= 0) return null
    val modulePixels = sidePixels / totalModules
    if (modulePixels < 1) return null
    // 位图边长取整数个模块，保证每个模块边界都落在整像素上。
    val side = modulePixels * totalModules

    val pixels = IntArray(side * side)
    Arrays.fill(pixels, light)
    for (y in 0 until matrix.size) {
        for (x in 0 until matrix.size) {
            if (!matrix.isDark(x, y)) continue
            val left = (x + QUIET_ZONE_MODULES) * modulePixels
            val top = (y + QUIET_ZONE_MODULES) * modulePixels
            for (row in 0 until modulePixels) {
                val start = (top + row) * side + left
                Arrays.fill(pixels, start, start + modulePixels, dark)
            }
        }
    }

    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
    return bitmap
}
