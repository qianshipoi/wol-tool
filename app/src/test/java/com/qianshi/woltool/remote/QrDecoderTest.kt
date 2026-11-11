package com.qianshi.woltool.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [QrDecoder] 的 JVM 往返测试：用 ZXing 自己生成二维码（BitMatrix → 灰度 / ARGB 像素），
 * 再交给被测代码解码。这里刻意不碰 android.graphics.Bitmap，保持纯 JVM 可运行。
 */
class QrDecoderTest {

    private val relayPayload =
        "woltool://relay?v=1&u=http://100.64.0.2:8787&t=Zx9Qw3Er7Ty1Ui5O"

    @Test
    fun `decodes ascii payload from gray plane`() {
        val image = qrImage(relayPayload)
        val gray = image.toGray()

        assertEquals(relayPayload, QrDecoder.decodeGray(gray, image.width, image.height))
    }

    @Test
    fun `decodes long payload with chinese and special characters`() {
        val token = "Zx9Qw3Er7Ty1Ui5Op2As4Df6Gh8Jk0Lm"
        val payload = "woltool://relay?v=1&u=http%3A%2F%2F100.101.102.103%3A8787" +
            "&t=$token$token&备注=中继手机·客厅&note=%E5%B1%B1%E4%B8%9C"
        // 载荷超过 100 个字符，覆盖中文字符与 URL 特殊字符。
        assertTrue("测试载荷应超过 100 个字符", payload.length > 100)

        val image = qrImage(payload, size = 600)
        val gray = image.toGray()

        assertEquals(payload, QrDecoder.decodeGray(gray, image.width, image.height))
    }

    @Test
    fun `decodes gray frame rotated by 90 180 270 degrees`() {
        val upright = qrImage(relayPayload)

        listOf(90, 180, 270).forEach { degrees ->
            // 模拟相机输出：画面相对正向逆时针转了 degrees 度，
            // 因此 rotationDegrees = degrees 表示「顺时针转回 degrees 度即正向」。
            val rotated = upright.rotateCounterClockwise(degrees)
            val gray = rotated.toGray()

            assertEquals(
                "rotationDegrees=$degrees",
                relayPayload,
                QrDecoder.decodeGray(gray, rotated.width, rotated.height, degrees),
            )
        }
    }

    @Test
    fun `decodes rotated non-square frame with swapped dimensions`() {
        // 相机 Y 平面通常是 640×480 的横图；rotationDegrees = 90 后宽高必须互换，
        // 这里验证旋转后的尺寸传递与解码都正确。
        val upright = qrImageOnCanvas(
            content = relayPayload,
            canvasWidth = 640,
            canvasHeight = 480,
            qrSize = 320,
        )
        val rotated = upright.rotateCounterClockwise(90)

        assertEquals(480, rotated.width)
        assertEquals(640, rotated.height)
        assertEquals(
            relayPayload,
            QrDecoder.decodeGray(rotated.toGray(), rotated.width, rotated.height, 90),
        )
    }

    @Test
    fun `decodes argb pixels`() {
        val image = qrImage(relayPayload)

        assertEquals(
            relayPayload,
            QrDecoder.decodePixels(image.pixels, image.width, image.height),
        )
    }

    @Test
    fun `returns null for random noise`() {
        val random = Random(20261111)
        val size = 240
        val gray = ByteArray(size * size).also { random.nextBytes(it) }
        val pixels = IntArray(size * size) { random.nextInt() }

        assertNull(QrDecoder.decodeGray(gray, size, size))
        assertNull(QrDecoder.decodePixels(pixels, size, size))
    }

    @Test
    fun `returns null for blank frame`() {
        val size = 160
        assertNull(QrDecoder.decodeGray(ByteArray(size * size), size, size))
        assertNull(QrDecoder.decodePixels(IntArray(size * size) { 0xFFFFFFFF.toInt() }, size, size))
    }

    @Test
    fun `returns null for empty or invalid gray input`() {
        assertNull(QrDecoder.decodeGray(ByteArray(0), 0, 0))
        assertNull(QrDecoder.decodeGray(ByteArray(0), 64, 64))
        assertNull(QrDecoder.decodeGray(ByteArray(64 * 64), 64, 64, rotationDegrees = 45))
        assertNull(QrDecoder.decodeGray(ByteArray(16), 64, 64))
        assertNull(QrDecoder.decodeGray(ByteArray(64 * 64), -64, 64))
    }

    @Test
    fun `returns null for empty or invalid pixel input`() {
        assertNull(QrDecoder.decodePixels(IntArray(0), 0, 0))
        assertNull(QrDecoder.decodePixels(IntArray(0), 64, 64))
        assertNull(QrDecoder.decodePixels(IntArray(16), 64, 64))
        assertNull(QrDecoder.decodePixels(IntArray(64 * 64), 0, 64))
    }

    @Test
    fun `tolerates trailing padding in gray buffer`() {
        // 相机 Y 平面的长度可能大于 width × height（rowStride 填充），调用方复制时会去掉填充；
        // 这里确认多出来的尾部字节不影响解码。
        val image = qrImage(relayPayload)
        val padded = image.toGray().copyOf(image.width * image.height + 512)

        assertEquals(
            relayPayload,
            QrDecoder.decodeGray(padded, image.width, image.height),
        )
    }

    // ---- 测试辅助：用 ZXing 生成二维码并铺成像素 ----

    private data class TestImage(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
    ) {
        /** 把 ARGB 像素转成紧凑灰度数据，模拟相机 Y 平面。 */
        fun toGray(): ByteArray {
            val gray = ByteArray(pixels.size)
            for (index in pixels.indices) {
                val color = pixels[index]
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                gray[index] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
            }
            return gray
        }

        /** 逆时针旋转 90/180/270 度，用来模拟「需要顺时针转回」的相机帧。 */
        fun rotateCounterClockwise(degrees: Int): TestImage = when (degrees) {
            0 -> this
            90 -> {
                val destination = IntArray(pixels.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        destination[(width - 1 - x) * height + y] = pixels[y * width + x]
                    }
                }
                TestImage(destination, height, width)
            }
            180 -> {
                val destination = IntArray(pixels.size)
                for (index in pixels.indices) {
                    destination[index] = pixels[pixels.size - 1 - index]
                }
                TestImage(destination, width, height)
            }
            270 -> {
                val destination = IntArray(pixels.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        destination[x * height + (height - 1 - y)] = pixels[y * width + x]
                    }
                }
                TestImage(destination, height, width)
            }
            else -> error("unsupported degrees: $degrees")
        }
    }

    private fun qrImage(content: String, size: Int = 480): TestImage {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return TestImage(pixels, size, size)
    }

    /** 把二维码居中铺在一张更大的画布上，用来模拟非正方形的相机帧。 */
    private fun qrImageOnCanvas(
        content: String,
        canvasWidth: Int,
        canvasHeight: Int,
        qrSize: Int,
    ): TestImage {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, qrSize, qrSize, hints)
        val pixels = IntArray(canvasWidth * canvasHeight) { WHITE }
        val left = (canvasWidth - qrSize) / 2
        val top = (canvasHeight - qrSize) / 2
        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                if (matrix[x, y]) {
                    pixels[(top + y) * canvasWidth + (left + x)] = BLACK
                }
            }
        }
        return TestImage(pixels, canvasWidth, canvasHeight)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
