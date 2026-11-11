package com.qianshi.woltool.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 纯 JVM 的二维码生成器（只依赖 ZXing，不含任何 android.* 引用），
 * 方便在单元测试里直接验证矩阵内容。
 */
object QrCodeGenerator {

    /**
     * 二维码模块矩阵。
     *
     * @param size 边长（模块数），不含 quiet zone。
     * @param dark 逐行存储的模块颜色，索引为 `y * size + x`，true 表示深色模块。
     */
    data class QrMatrix(val size: Int, val dark: BooleanArray) {

        fun isDark(x: Int, y: Int): Boolean =
            x >= 0 && y >= 0 && x < size && y < size && dark[y * size + x]
    }

    /**
     * 把文本编码成二维码矩阵。
     *
     * 内容为空、过长或编码失败时返回 null，不抛异常。
     */
    fun encode(
        content: String,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): QrMatrix? {
        if (content.isEmpty()) return null
        return try {
            val hints = mapOf<EncodeHintType, Any>(
                // 静区由渲染层自己补，保证像素边缘对齐。
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to errorCorrectionLevel,
            )
            // 宽高传 0 时 ZXing 输出逐模块矩阵（1 像素 = 1 模块）。
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            if (size <= 0 || matrix.height != size) return null
            val dark = BooleanArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    dark[offset + x] = matrix.get(x, y)
                }
            }
            QrMatrix(size = size, dark = dark)
        } catch (exception: Exception) {
            // 内容过长、含不可编码字符等情况统一降级为“无法生成二维码”。
            null
        }
    }
}
