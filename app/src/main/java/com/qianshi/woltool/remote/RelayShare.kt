package com.qianshi.woltool.remote

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 中继配置的可分享快照：远程手机扫码后填入的地址与令牌。
 *
 * 分享码是一段纯文本，格式固定为：
 * `woltool://relay?v=1&u=<encoded url>&t=<encoded token>[&n=<encoded name>]`
 *
 * 所有参数值都用 `URLEncoder`（UTF-8）编码，并把 `+` 统一替换成 `%20`，
 * 这样同一串文本既能印成二维码，也能直接复制粘贴到输入框。
 */
data class RelayShare(
    /** 已规范化的中继地址，例如 http://100.101.102.103:8787，无结尾斜杠。 */
    val url: String,
    /** 中继令牌原文。 */
    val token: String,
    /** 可选备注，最多 32 个字符。 */
    val name: String = "",
) {

    /** 生成分享码（v1 格式）。 */
    fun encode(): String {
        val builder = StringBuilder(SCHEME_PREFIX)
        builder.append(percentEncode(url))
        builder.append("&t=").append(percentEncode(token))
        val safeName = truncateName(name)
        if (safeName.isNotEmpty()) {
            builder.append("&n=").append(percentEncode(safeName))
        }
        return builder.toString()
    }

    companion object {

        private const val SCHEME_PREFIX = "woltool://relay?v=1&u="
        private const val SCHEME = "woltool://relay"
        private const val SUPPORTED_VERSION = "1"
        private const val MAX_NAME_LENGTH = 32
        private const val MIN_TOKEN_LENGTH = 8

        private const val MESSAGE_EMPTY = "请扫描 WOL 中继二维码"
        private const val MESSAGE_NOT_RELAY = "这不是 WOL 中继二维码"
        private const val MESSAGE_UNSUPPORTED_VERSION = "二维码版本不受支持，请升级应用"
        private const val MESSAGE_BAD_URL = "二维码中的中继地址格式不正确"
        private const val MESSAGE_BAD_TOKEN = "二维码中的中继令牌无效"

        /**
         * 解析分享码。
         *
         * 容错点：scheme/host 大小写、参数顺序、未知参数、缺少 `v`、首尾空白、
         * 地址末尾斜杠、多余的 URL fragment。失败时返回的中文提示可直接展示给用户。
         */
        fun decode(raw: String): Result<RelayShare> {
            val text = raw.trim()
            if (text.isEmpty()) {
                return Result.failure(IllegalArgumentException(MESSAGE_EMPTY))
            }

            // 二维码里不该有 fragment，但手工粘贴时可能出现，先截断再解析。
            val fragmentIndex = text.indexOf('#')
            val payload = if (fragmentIndex >= 0) text.substring(0, fragmentIndex) else text
            if (payload.length < SCHEME.length ||
                !payload.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)
            ) {
                return Result.failure(IllegalArgumentException(MESSAGE_NOT_RELAY))
            }

            val remainder = payload.substring(SCHEME.length)
            val query = when {
                remainder.isEmpty() -> ""
                remainder.startsWith("?") -> remainder.substring(1)
                else -> return Result.failure(IllegalArgumentException(MESSAGE_NOT_RELAY))
            }
            val params = parseQuery(query)

            // 缺少版本号时按 v1 处理，方便以后兼容更早的二维码。
            val version = params["v"]?.trim().orEmpty().ifEmpty { SUPPORTED_VERSION }
            if (version != SUPPORTED_VERSION) {
                return Result.failure(IllegalArgumentException(MESSAGE_UNSUPPORTED_VERSION))
            }

            val url = RemoteClient().normalizeBaseUrl(params["u"].orEmpty())
                ?: return Result.failure(IllegalArgumentException(MESSAGE_BAD_URL))
            val token = params["t"].orEmpty().trim()
            if (token.length < MIN_TOKEN_LENGTH) {
                return Result.failure(IllegalArgumentException(MESSAGE_BAD_TOKEN))
            }
            return Result.success(
                RelayShare(
                    url = url,
                    token = token,
                    name = truncateName(params["n"].orEmpty()),
                ),
            )
        }

        /** 解析 query 部分；重复参数只保留第一个值，无法解码的值按原文保留。 */
        private fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val params = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
                val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
                val key = decodeParam(rawKey).trim()
                if (key.isEmpty() || params.containsKey(key)) continue
                params[key] = decodeParam(rawValue)
            }
            return params
        }

        private fun decodeParam(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        private fun percentEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** 备注按 32 个字符截断，避免把 emoji 的代理对切一半。 */
        private fun truncateName(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
            var end = MAX_NAME_LENGTH
            if (trimmed[end - 1].isHighSurrogate()) end -= 1
            return trimmed.substring(0, end)
        }
    }
}
