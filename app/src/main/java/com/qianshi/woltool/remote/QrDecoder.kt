package com.qianshi.woltool.remote

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * 二维码解码工具：只负责把「像素」还原成「文本」，不解析业务含义。
 *
 * - [decodeGray]：相机 YUV 帧的 Y 平面（灰度）直接解码，扫码预览的主路径。
 * - [decodePixels]：ARGB 像素解码，单元测试与相册路径的中间层。
 * - [decodeBitmap]：相册图片解码。
 *
 * 约定：只识别二维码（QR_CODE）；识别失败一律返回 null，绝不抛异常，也不打印日志
 * （分享码里含中继地址与令牌，属于敏感信息）。
 */
object QrDecoder {

    /**
     * 让 ZXing 用 UTF-8 解释二维码里的字节，并开启 TRY_HARDER 提高手机屏幕翻拍的成功率。
     * 不启用 PURE_BARCODE：扫码场景里二维码带取景背景与透视变形，纯码模式反而更容易失败。
     */
    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * 相册路径允许的最大像素数（约 5000×5000）。超过后不再铺成 IntArray，避免内存抖动；
     * 调用方（相册导入）已经会把长边压缩到 2000px 以内。
     */
    private const val MAX_BITMAP_PIXELS = 25_000_000

    /**
     * 从相机 YUV 帧的 Y 平面解码二维码。
     *
     * @param gray 紧凑排列的灰度数据，长度至少为 [dataWidth] × [dataHeight]（允许尾部有多余填充）。
     * @param dataWidth Y 平面宽度，即 `ImageProxy.width`。
     * @param dataHeight Y 平面高度，即 `ImageProxy.height`。
     * @param rotationDegrees 让画面转为正向所需的顺时针角度，取值 0/90/180/270，
     *   通常直接传 `imageProxy.imageInfo.rotationDegrees`。
     */
    fun decodeGray(
        gray: ByteArray,
        dataWidth: Int,
        dataHeight: Int,
        rotationDegrees: Int = 0,
    ): String? {
        if (dataWidth <= 0 || dataHeight <= 0) return null
        if (dataWidth.toLong() * dataHeight.toLong() > gray.size) return null
        if (rotationDegrees != 0 && rotationDegrees != 90 &&
            rotationDegrees != 180 && rotationDegrees != 270
        ) {
            return null
        }
        return runCatching {
            if (rotationDegrees == 0) {
                decodePlane(gray, dataWidth, dataHeight)
            } else {
                // ZXing 的 PlanarYUVLuminanceSource / RGBLuminanceSource 都不支持 rotateCounterClockwise()
                // （isRotateSupported == false），所以这里自己把灰度矩阵转正后再交给 ZXing。
                val frame = rotateGray(gray, dataWidth, dataHeight, rotationDegrees)
                decodePlane(frame.data, frame.width, frame.height)
            }
        }.getOrNull()
    }

    /** 从 ARGB 像素（`Bitmap.getPixels` 的格式）解码二维码。 */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > pixels.size) return null
        return runCatching {
            decodeSource(RGBLuminanceSource(width, height, pixels))
        }.getOrNull()
    }

    /** 从相册图片解码二维码；Bitmap 由调用方负责回收。 */
    fun decodeBitmap(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return@runCatching null
        if (width.toLong() * height.toLong() > MAX_BITMAP_PIXELS) return@runCatching null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        decodePixels(pixels, width, height)
    }.getOrNull()

    private fun decodePlane(gray: ByteArray, width: Int, height: Int): String? {
        // 这里传入的 gray 已经是紧凑的 width × height 数据：不带 rowStride 填充，
        // 因此 crop 的区域与数据尺寸完全一致。
        val source = PlanarYUVLuminanceSource(
            gray,
            width,
            height,
            0,
            0,
            width,
            height,
            /* reverseHorizontal = */ false,
        )
        return decodeSource(source)
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        return try {
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS)
            result.text?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            // NotFoundException / ChecksumException / FormatException 等都属于「这一帧没扫到」。
            null
        } finally {
            reader.reset()
        }
    }

    /** 内部使用的一维灰度帧（紧凑排列）。 */
    private class GrayFrame(val data: ByteArray, val width: Int, val height: Int)

    /**
     * 把灰度矩阵顺时针旋转 [degrees] 度（0/90/180/270）。90 与 270 时宽高互换。
     */
    private fun rotateGray(
        source: ByteArray,
        width: Int,
        height: Int,
        degrees: Int,
    ): GrayFrame = when (degrees) {
        90 -> GrayFrame(rotateGrayClockwise(source, width, height), height, width)
        180 -> GrayFrame(rotateGrayHalfTurn(source), width, height)
        else -> GrayFrame(rotateGrayCounterClockwise(source, width, height), height, width)
    }

    /** 顺时针 90°：目标宽 = height，目标高 = width。 */
    private fun rotateGrayClockwise(source: ByteArray, width: Int, height: Int): ByteArray {
        val destination = ByteArray(source.size)
        val destinationWidth = height
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                destination[x * destinationWidth + (height - 1 - y)] = source[rowStart + x]
            }
        }
        return destination
    }

    /** 逆时针 90°（等价于顺时针 270°）。 */
    private fun rotateGrayCounterClockwise(source: ByteArray, width: Int, height: Int): ByteArray {
        val destination = ByteArray(source.size)
        val destinationWidth = height
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                destination[(width - 1 - x) * destinationWidth + y] = source[rowStart + x]
            }
        }
        return destination
    }

    private fun rotateGrayHalfTurn(source: ByteArray): ByteArray {
        val destination = ByteArray(source.size)
        val last = source.size - 1
        for (index in source.indices) {
            destination[index] = source[last - index]
        }
        return destination
    }
}
