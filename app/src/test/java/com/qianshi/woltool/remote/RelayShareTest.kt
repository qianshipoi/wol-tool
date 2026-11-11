package com.qianshi.woltool.remote

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证中继分享码的编解码契约，以及二维码矩阵能被 ZXing 反解回原文。
 *
 * 这些用例都是纯 JVM 测试：不依赖 android.* / org.json。
 */
class RelayShareTest {

    private val url = "http://100.101.102.103:8787"
    private val token = "kR7cQ2mZ9pXw4TnLb6yHsA"

    @Test
    fun `round trips ordinary token`() {
        val share = RelayShare(url = url, token = token)
        val decoded = RelayShare.decode(share.encode()).getOrThrow()
        assertEquals(url, decoded.url)
        assertEquals(token, decoded.token)
        assertEquals("", decoded.name)
    }

    @Test
    fun `round trips token with special characters`() {
        // 令牌里可能出现 - _ = + / 这类字符，必须原样往返。
        val special = "aZ09-_.+=/x"
        val share = RelayShare(url = url, token = special)
        val decoded = RelayShare.decode(share.encode()).getOrThrow()
        assertEquals(special, decoded.token)
    }

    @Test
    fun `round trips chinese name`() {
        val share = RelayShare(url = url, token = token, name = "家里客厅的中继")
        val decoded = RelayShare.decode(share.encode()).getOrThrow()
        assertEquals("家里客厅的中继", decoded.name)
    }

    @Test
    fun `truncates long name to 32 characters`() {
        val longName = "一二三四五六七八九十".repeat(4)
        assertEquals(40, longName.length)
        val decoded = RelayShare.decode(
            RelayShare(url = url, token = token, name = longName).encode(),
        ).getOrThrow()
        assertEquals(32, decoded.name.length)
        assertEquals(longName.substring(0, 32), decoded.name)
    }

    @Test
    fun `encode uses v1 prefix and never emits plus`() {
        val share = RelayShare(url = url, token = "a b+c/d=e_f-g", name = "含空格 的名字")
        val encoded = share.encode()
        assertTrue(encoded.startsWith("woltool://relay?v=1&u="))
        assertFalse(encoded.contains("+"))
    }

    @Test
    fun `fails on blank input`() {
        assertEquals("请扫描 WOL 中继二维码", failureMessage(""))
        assertEquals("请扫描 WOL 中继二维码", failureMessage("   "))
    }

    @Test
    fun `fails on plain url`() {
        assertEquals("这不是 WOL 中继二维码", failureMessage("http://100.101.102.103:8787"))
        assertEquals("这不是 WOL 中继二维码", failureMessage("https://example.com"))
    }

    @Test
    fun `fails when token missing`() {
        val raw = "woltool://relay?v=1&u=${encodeValue(url)}"
        assertEquals("二维码中的中继令牌无效", failureMessage(raw))
    }

    @Test
    fun `fails when token is too short`() {
        val raw = "woltool://relay?v=1&u=${encodeValue(url)}&t=short"
        assertEquals("二维码中的中继令牌无效", failureMessage(raw))
    }

    @Test
    fun `fails on unsupported version`() {
        val raw = "woltool://relay?v=2&u=${encodeValue(url)}&t=$token"
        assertEquals("二维码版本不受支持，请升级应用", failureMessage(raw))
    }

    @Test
    fun `fails on invalid relay url`() {
        val raw = "woltool://relay?v=1&u=${encodeValue("not a url")}&t=$token"
        assertEquals("二维码中的中继地址格式不正确", failureMessage(raw))
        val notHttp = "woltool://relay?v=1&u=${encodeValue("ftp://100.64.0.2:8787")}&t=$token"
        assertEquals("二维码中的中继地址格式不正确", failureMessage(notHttp))
    }

    @Test
    fun `fails on other woltool scheme`() {
        val raw = "woltool://wake?v=1&u=${encodeValue(url)}&t=$token"
        assertEquals("这不是 WOL 中继二维码", failureMessage(raw))
    }

    @Test
    fun `tolerates upper case scheme`() {
        val raw = "WOLTOOL://RELAY?v=1&u=${encodeValue(url)}&t=$token"
        val decoded = RelayShare.decode(raw).getOrThrow()
        assertEquals(url, decoded.url)
        assertEquals(token, decoded.token)
    }

    @Test
    fun `tolerates shuffled params and extra params`() {
        val shuffled = "woltool://relay?t=$token&n=${encodeValue("书房")}&v=1&u=${encodeValue(url)}"
        val decoded = RelayShare.decode(shuffled).getOrThrow()
        assertEquals(url, decoded.url)
        assertEquals(token, decoded.token)
        assertEquals("书房", decoded.name)

        val extra = "woltool://relay?x=1&u=${encodeValue(url)}&t=$token&v=1&unknown=abc"
        assertEquals(url, RelayShare.decode(extra).getOrThrow().url)
    }

    @Test
    fun `tolerates surrounding whitespace and missing version`() {
        val raw = "  woltool://relay?u=${encodeValue(url)}&t=$token\n "
        val decoded = RelayShare.decode(raw).getOrThrow()
        assertEquals(url, decoded.url)
        assertEquals(token, decoded.token)
    }

    @Test
    fun `tolerates trailing slash in url`() {
        val raw = "woltool://relay?v=1&u=${encodeValue("$url/")}&t=$token"
        assertEquals(url, RelayShare.decode(raw).getOrThrow().url)
    }

    @Test
    fun `generates qr matrix`() {
        val matrix = QrCodeGenerator.encode(RelayShare(url, token, "家里客厅的中继").encode())
            ?: throw AssertionError("二维码应生成成功")
        val size = matrix.size
        assertTrue(size > 20)
        // 矩阵不能全白：至少要有定位图案。
        assertTrue((0 until size).any { y -> (0 until size).any { x -> matrix.isDark(x, y) } })
    }

    @Test
    fun `qr matrix decodes back to share code`() {
        val content = RelayShare(url = url, token = token, name = "家里客厅的中继").encode()
        val matrix = QrCodeGenerator.encode(content)
            ?: throw AssertionError("二维码应生成成功")
        assertEquals(content, decodeMatrix(matrix))
    }

    @Test
    fun `returns null for empty qr content`() {
        assertNull(QrCodeGenerator.encode(""))
    }

    private fun failureMessage(raw: String): String {
        val result = RelayShare.decode(raw)
        assertTrue("应解析失败：$raw", result.isFailure)
        return result.exceptionOrNull()?.message.orEmpty()
    }

    private fun encodeValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 把矩阵铺成 RGB 像素（2×2 像素/模块 + 4 模块静区）后用 ZXing 反解，
     * 模拟相机看到的实际图像。
     */
    private fun decodeMatrix(matrix: QrCodeGenerator.QrMatrix): String {
        val scale = 2
        val quietZone = 4
        val modules = matrix.size + quietZone * 2
        val side = modules * scale
        val pixels = IntArray(side * side)
        java.util.Arrays.fill(pixels, WHITE)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isDark(x, y)) continue
                val left = (x + quietZone) * scale
                val top = (y + quietZone) * scale
                for (row in 0 until scale) {
                    for (column in 0 until scale) {
                        pixels[(top + row) * side + left + column] = BLACK
                    }
                }
            }
        }
        val source = RGBLuminanceSource(side, side, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(bitmap).text
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
    }
}
