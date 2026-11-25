// HTTP 传输层的 Android 实现（OkHttp）。
//
// 为什么实现放在 androidMain、接口放在 commonMain：commonMain 看不到 java.* 与 Android API，
// 真正的网络库只能落在平台侧。这样分层之后，数据层的解析与错误映射全部留在 commonMain，
// 可以用普通 JVM 单测覆盖（不连网也能测），换库时也只动这一个文件。
//
// 为什么选 OkHttp 而不是 Ktor：
// - OkHttp 是纯 JVM 库，放在 androidMain 就不参与 KMP 变体/metadata 协商，与 Kuikly
//   2.17.0-2.1.21 + AGP 7.4.2 之间没有"变体解析"层面的冲突面；
// - 本机 Gradle 缓存里已有 okhttp 4.10.0 + okio 3.0.0（Kuikly 传递依赖带进来的），
//   等于零新增下载风险；
// - Ktor 2.3.10 未在本机缓存中，且它的客户核心要进 commonMain 参与变体解析，风险面更大。
//
// 代价（写在这里，别指望以后还记得）：没有 HTTP/2 与 gzip 自动解压之外的高级特性；
// 大图走的是系统图片库而不是这里的传输层，所以这个限制只影响 JSON 接口。

package com.eightfs.lumigather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Content-Type 头名，取它来判断媒体地址是不是图片（自检与后续收藏功能都要看这个）。
private const val HEADER_CONTENT_TYPE = "Content-Type"

// 写请求的方法名。集中成常量而不是散落字面量：这两个值要与接口语义严格对应，写错会静默发错方法。
private const val WRITE_METHOD_POST = "POST"
private const val WRITE_METHOD_DELETE = "DELETE"

/**
 * 基于 OkHttp 的 [HttpTransport]。
 *
 * 请求是阻塞式的，因此对外仍是 suspend：这里用 [Dispatchers.IO] 把阻塞调用切到 IO 线程池，
 * 保证调用方（UI 协程）不会被网络阻塞住。
 */
class OkHttpHttpTransport(
    private val client: OkHttpClient = defaultClient()
) : HttpTransport {

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        timeoutMs: Long
    ): HttpRawResponse = withContext(Dispatchers.IO) {
        executeBlocking(url, query, timeoutMs)
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse = withContext(Dispatchers.IO) {
        executeWriteBlocking(method = WRITE_METHOD_POST, url, body, contentType, headers, timeoutMs)
    }

    override suspend fun delete(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse = withContext(Dispatchers.IO) {
        executeWriteBlocking(method = WRITE_METHOD_DELETE, url, body, contentType, headers, timeoutMs)
    }

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        timeoutMs: Long,
    ): HttpBytesResponse = withContext(Dispatchers.IO) {
        val fullUrl = UrlBuilder.build(url, query)
        val request = Request.Builder().url(fullUrl).get().build()
        callWithTimeout(timeoutMs).newCall(request).execute().use { response ->
            // bytes() 直接读原始字节：图片/GIF 走文本解码会被改坏
            HttpBytesResponse(
                statusCode = response.code,
                bytes = response.body?.bytes() ?: ByteArray(0),
                contentType = response.header(HEADER_CONTENT_TYPE),
            )
        }
    }

    // 写请求：body 由调用方序列化好（登录那边用 JSONObject 拼，密码里的引号会被正确转义），
    // 这里只负责按指定 Content-Type 与 HTTP 方法发出去，并把额外请求头（如 Bearer token）带上。
    private fun executeWriteBlocking(
        method: String,
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse {
        val requestBody = body.toRequestBody(contentType.toMediaTypeOrNull())
        val requestBuilder = Request.Builder().url(url)
        when (method) {
            WRITE_METHOD_DELETE -> requestBuilder.delete(requestBody)
            else -> requestBuilder.post(requestBody)
        }
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        callWithTimeout(timeoutMs).newCall(requestBuilder.build()).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            return HttpRawResponse(response.code, bodyText, response.header(HEADER_CONTENT_TYPE))
        }
    }

    private fun executeBlocking(
        url: String,
        query: Map<String, String>,
        timeoutMs: Long
    ): HttpRawResponse {
        // 地址在 commonMain 里按 RFC 3986 编好，这里不做二次编码 —— OkHttp 的 toHttpUrl
        // 会保留已有的 %XX 转义，所以中文路径不会变成双重编码。
        val fullUrl = UrlBuilder.build(url, query)
        val request = Request.Builder().url(fullUrl).get().build()
        callWithTimeout(timeoutMs).newCall(request).execute().use { response ->
            // 非 2xx 时 OkHttp 的 body 仍是可读的（服务端常在这里放 {"error": "..."}），
            // 所以统一按字符串读出来，交给 ApiClient 判状态码与抽 error。
            val bodyText = response.body?.string().orEmpty()
            return HttpRawResponse(response.code, bodyText, response.header(HEADER_CONTENT_TYPE))
        }
    }

    // 每次调用都从共享的 client 派生一个带总超时的副本：newBuilder 复用连接池与调度器，
    // 不会因为"每次新建 client"而丢连接复用。
    private fun callWithTimeout(timeoutMs: Long): OkHttpClient {
        if (timeoutMs <= 0L) return client
        return client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    }

    companion object {
        // 默认 client：连接超时与读超时都按本机实测值给（局域网内 1 秒内连通，
        // 首次统计大目录最慢 3.4 秒），总超时由每次调用单独收紧。
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
