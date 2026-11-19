// 服务端 API 的唯一入口：把 HTTP + JSON 的细节关在这里，对上只给 [ApiResult]。
//
// 三个必须照做的后端事实（都是实测出来的，改代码前先读这三条）：
// 1) 目录参数名是 root（绝对路径），不是 dir。传错名字不会报错，只会静默回落到全局 fsRoot，
//    表现是"8 个分类看到同一批图"——最难查的一种错，因为它不报错。
// 2) 递归必须显式传 recursive=true。默认 false 只返回分类根目录那一层的图
//    （实测崩坏3：非递归 1 张，递归 1262 张）。
// 3) images[].url 是相对路径且已 URL 编码，拼绝对地址只能补前缀，不能重新编码。
//
// 错误一律转成值：网络异常、非 2xx、200 里塞 error、JSON 不合法，四种都在这里收敛成
// [ApiError]，绝不让异常穿到 UI。

package com.eightfs.lumigather.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

// 所有接口共用 /api 前缀（与网页版 API_BASE 一致）。
private const val API_PREFIX = "/api"

private const val ENDPOINT_SITE_CONFIG = "/lumigather"
private const val ENDPOINT_DIRECTORIES = "/directories"
private const val ENDPOINT_IMAGES = "/images"
private const val ENDPOINT_STATUS = "/status"

// 查询参数名。参数名必须与后端逐字一致，写错了后端不会报错（见文件头第 1 条）。
private const val QUERY_ROOT = "root"
private const val QUERY_OFFSET = "offset"
private const val QUERY_LIMIT = "limit"
private const val QUERY_RECURSIVE = "recursive"

// 2xx 视为成功。用区间而不是 == 200，是为了兼容 206/204 这类合法返回。
private val HTTP_SUCCESS_RANGE = 200..299

// 失败时留多少字符的 body 片段给日志。够看清 "ENOENT: no such file..." 这种开头，
// 又不至于把整个响应体（可能含 token）写进日志。
private const val ERROR_BODY_SNIPPET_LENGTH = 200

private const val RECURSIVE_TRUE = "true"
private const val RECURSIVE_FALSE = "false"

/**
 * 一个 baseUrl + 一个传输层，就是全部状态。[baseUrl] 允许带子路径前缀
 * （服务端部署在 /lumigather 之类前缀下时，前缀要一起带上）。
 */
class ApiClient(
    rawBaseUrl: String,
    private val transport: HttpTransport,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
) {
    // 归一化后保存：末尾斜杠留着的后果是拼出 "http://h:8081//api/status"，
    // 部分服务端会把它当不同路径直接 404。
    val baseUrl: String = normalizeBaseUrl(rawBaseUrl)

    // 地址没配时不该发请求，直接给 InvalidConfig，让 UI 引导用户去设置页。
    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty()

    // GET /api/lumigather：站点标题、分类、分类→路径映射、每行图数。
    suspend fun fetchSiteConfig(): ApiResult<SiteConfig> =
        getJson(ENDPOINT_SITE_CONFIG, emptyMap(), ::parseSiteConfig)

    // GET /api/directories?root=...：某一级目录下的子项名字列表。
    // root 为空直接本地拦掉——空 root 发出去会命中后端"回落到 fsRoot"的行为，回来的数据看着正常
    // 但其实是另一个目录，属于静默错误。
    suspend fun fetchDirectories(root: String): ApiResult<DirectoryListing> {
        if (root.isBlank()) return ApiResult.Err(ApiError.BadArgument("fetchDirectories 需要非空 root"))
        return getJson(ENDPOINT_DIRECTORIES, mapOf(QUERY_ROOT to root), ::parseDirectoryListing)
    }

    // GET /api/images?root=...&recursive=...&offset=...&limit=...：一页图片。
    // recursive 默认 true：本 App 的浏览单位是"分类"，要的是分类下所有层的图。
    suspend fun fetchImages(
        root: String,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_SIZE,
        recursive: Boolean = true
    ): ApiResult<PagedImages> {
        if (root.isBlank()) return ApiResult.Err(ApiError.BadArgument("fetchImages 需要非空 root"))
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        return getJson(
            ENDPOINT_IMAGES,
            mapOf(
                QUERY_ROOT to root,
                QUERY_RECURSIVE to if (recursive) RECURSIVE_TRUE else RECURSIVE_FALSE,
                QUERY_OFFSET to safeOffset.toString(),
                QUERY_LIMIT to safeLimit.toString()
            ),
            ::parsePagedImages
        )
    }

    // GET /api/status：在线状态、版本、监听端口。底部状态栏用它。
    suspend fun fetchStatus(): ApiResult<ServerStatus> =
        getJson(ENDPOINT_STATUS, emptyMap(), ::parseServerStatus)

    // 相对地址 → 绝对地址；已是绝对地址则原样返回。
    fun resolveUrl(relativeOrAbsolute: String): String = joinUrl(baseUrl, relativeOrAbsolute)

    // 取某张图可直接加载的地址。UI 通常用不到它（仓库层已把 absoluteUrl 填进模型），
    // 保留给自检与"模型没走仓库"的兜底场景。
    fun mediaUrl(item: ImageItem): String = mediaUrlOf(baseUrl, item)

    // 四个 GET 共用的执行骨架：发请求 → 判状态码 → 解 JSON → 抽 error → 映射模型。
    private suspend fun <T> getJson(
        endpoint: String,
        query: Map<String, String>,
        parse: (JSONObject) -> T
    ): ApiResult<T> {
        if (!isConfigured) return ApiResult.Err(ApiError.InvalidConfig("baseUrl 为空"))

        val url = baseUrl + API_PREFIX + endpoint
        val raw = try {
            transport.get(url, query, requestTimeoutMs)
        } catch (cancelled: CancellationException) {
            // 协程取消不是"失败"，必须原样上抛，否则 awaitAll/作用域取消会被这里吞掉，
            // 表现是页面退出后请求还在跑、回调还在改状态。
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }
        return interpret(raw, parse)
    }

    private fun <T> interpret(raw: HttpRawResponse, parse: (JSONObject) -> T): ApiResult<T> {
        // 先判状态码：非 2xx 时 body 往往不是 JSON（或虽是 JSON 但只有 error），不值得再解析一遍。
        if (raw.statusCode !in HTTP_SUCCESS_RANGE) {
            val serverMessage = runCatching { extractServerError(JSONObject(raw.bodyText)) }
                .getOrDefault("")
            return ApiResult.Err(ApiError.Http(raw.statusCode, serverMessage))
        }

        val json = try {
            JSONObject(raw.bodyText)
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Parse(describeFailure(failure), bodySnippet(raw.bodyText)))
        }

        // 后端存在"200 + {"error": ...}"的路径（实测：root 指向不存在路径时，某些前缀下走这条）。
        // 不抽这个字段就会把一次失败当成"空目录"成功返回，UI 表现为"分类是空的"而没有任何错误提示。
        val serverError = extractServerError(json)
        if (serverError.isNotBlank()) {
            return ApiResult.Err(ApiError.Http(raw.statusCode, serverError))
        }

        return try {
            ApiResult.Ok(parse(json))
        } catch (failure: Throwable) {
            ApiResult.Err(ApiError.Parse(describeFailure(failure), bodySnippet(raw.bodyText)))
        }
    }

    // 截断 body 片段：只保留开头，避免把含敏感字段的完整响应写进日志。
    private fun bodySnippet(body: String): String =
        if (body.length <= ERROR_BODY_SNIPPET_LENGTH) body else body.take(ERROR_BODY_SNIPPET_LENGTH)
}
