// HTTP 传输层的抽象与查询串编码。
//
// 为什么抽一层接口：真正的 HTTP 实现（OkHttp）只能在 androidMain 出现 —— commonMain 拿不到
// java.* / Android API。把接口放 commonMain、实现放 androidMain，就得到两个好处：
// 一是数据层的解析/错误映射逻辑全部留在 commonMain，可用普通 JVM 单测覆盖（不需要连网）；
// 二是将来换 HTTP 库（OkHttp → 别的）只动一个实现类，ApiClient 与仓库一行不改。
//
// 编码规则单独抽 [UrlBuilder] 而不是交给 HTTP 库，是因为请求参数里的 root 是文件系统绝对路径，
// 含空格、中文、en dash(–)、方括号、圆括号。让库去猜编码容易出双编码/漏编码，自己按
// RFC 3986 做「非 unreserved 一律百分号编码」最稳，而且能用单测钉死。

package com.eightfs.lumigather.data

// 建立 TCP 连接的上限：局域网内正常在 1 秒内完成，10 秒还没连上基本就是地址错了。
const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000L

// 整次请求的上限：服务端首次统计大目录要扫盘，实测最慢 3.4 秒，30 秒留足余量。
const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000L

// 十六进制字母表，用于百分号编码的两位输出。大写是 RFC 3986 的推荐形式。
private const val HEX_DIGITS = "0123456789ABCDEF"

// 字节低位/高位的位宽，把 0..255 拆成两个十六进制半字节。
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F

// 单字节掩码：encodeToByteArray() 给的是有符号 Byte，按位与 0xFF 还原成 0..255。
private const val BYTE_MASK = 0xFF

// RFC 3986 里除字母数字外还允许直接出现在 query 的字符。其余一律百分号编码——
// 多编码几个字符是合法的（服务端解码后一样），漏编码则会直接 400/404。
private const val UNRESERVED_EXTRA_CHARS = "-._~"

private const val QUERY_ENTRY_SEPARATOR = '&'
private const val QUERY_KEY_VALUE_SEPARATOR = '='
private const val QUERY_PREFIX = '?'
private const val PERCENT_SIGN = '%'

/**
 * 一次 GET 的原始回包。数据层只关心状态码、正文与类型，不暴露 HTTP 库的类型，
 * 这样 [HttpTransport] 的实现换库时不会污染上层。
 */
data class HttpRawResponse(
    val statusCode: Int,
    val bodyText: String,
    val contentType: String? = null
)

/**
 * GET 请求的最小契约。
 *
 * [query] 用 Map 而不是拼好的字符串：调用方只表达"有这些参数"，
 * 具体怎么编码由 [UrlBuilder] 统一负责，避免每个调用点各编一套。
 */
interface HttpTransport {
    suspend fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): HttpRawResponse
}

/**
 * 查询串构造器：把 [url] 与 [query] 合成最终请求地址。
 *
 * 空 [query] 时原样返回 url —— 不额外补一个裸 "?"（后端对裸 ? 的容忍度不如干净地址）。
 */
object UrlBuilder {

    fun build(url: String, query: Map<String, String>): String {
        if (query.isEmpty()) return url
        val encoded = StringBuilder()
        for ((key, value) in query) {
            if (encoded.isNotEmpty()) encoded.append(QUERY_ENTRY_SEPARATOR)
            encoded.append(percentEncode(key))
            encoded.append(QUERY_KEY_VALUE_SEPARATOR)
            encoded.append(percentEncode(value))
        }
        return url + QUERY_PREFIX + encoded.toString()
    }

    /**
     * 严格百分号编码：只放过 `A-Z a-z 0-9 - . _ ~`，其余字符按 UTF-8 字节逐个编码。
     *
     * 用 UTF-8 字节而不是 Character 编码，是因为中文、en dash 这类字符在 URL 里必须以
     * 多字节形态出现（例如 '–' → "%E2%80%93"），按字符编会得到一个非法地址。
     */
    fun percentEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and BYTE_MASK
            if (isUnreserved(code)) {
                builder.append(code.toChar())
            } else {
                builder.append(PERCENT_SIGN)
                builder.append(HEX_DIGITS[code shr NIBBLE_BITS])
                builder.append(HEX_DIGITS[code and NIBBLE_MASK])
            }
        }
        return builder.toString()
    }

    // unreserved 判定：数字、大写字母、小写字母，以及 - . _ ~ 四个符号。
    private fun isUnreserved(code: Int): Boolean {
        if (code in CODE_DIGIT_START..CODE_DIGIT_END) return true
        if (code in CODE_UPPER_START..CODE_UPPER_END) return true
        if (code in CODE_LOWER_START..CODE_LOWER_END) return true
        return UNRESERVED_EXTRA_CHARS.indexOf(code.toChar()) >= 0
    }

    // ASCII 码位区间，避免在判定里散落数字字面量。
    private const val CODE_DIGIT_START = 0x30
    private const val CODE_DIGIT_END = 0x39
    private const val CODE_UPPER_START = 0x41
    private const val CODE_UPPER_END = 0x5A
    private const val CODE_LOWER_START = 0x61
    private const val CODE_LOWER_END = 0x7A
}
