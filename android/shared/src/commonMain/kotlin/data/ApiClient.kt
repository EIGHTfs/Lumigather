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
private const val ENDPOINT_LOGIN = "/login"
private const val ENDPOINT_LOGOUT = "/logout"

// 登录请求体的字段名（与后端 readBody 后取的键逐字一致）
private const val BODY_PASSWORD = "password"
private const val BODY_REMEMBER = "remember"

// 登出没有请求体语义，但后端要求 POST；发一个空 JSON 对象最省事且合法。
private const val EMPTY_JSON_BODY = "{}"

// 会话 token 的请求头。后端 extractToken 先看 cookie、再看 `Authorization: Bearer <token>`，
// 原生端没有 cookie 罐子，所以直接走 Bearer。
private const val HEADER_AUTHORIZATION = "Authorization"
private const val BEARER_PREFIX = "Bearer "

// 401：会话无效/未登录。读数接口目前免鉴权，这个分支主要给"将来要鉴权的接口"兜底，
// 以及把"过期就清 token"这件事集中在唯一一处。
private const val HTTP_UNAUTHORIZED = 401

// 401 但服务端没给原因时的兜底文案（可直接上屏）
private const val MESSAGE_SESSION_EXPIRED = "登录已过期，请重新登录"

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
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    /**
     * 401 回调：任何**非登录**请求收到 401 时触发一次。
     *
     * 放在这一层而不是各调用点：凭证失效是全局事实，每个调用点都自己判 401 一定会漏；
     * 而登录请求自己的 401 是"密码错"，不能当会话过期处理，所以由调用方用
     * `isAuthAttempt = true` 显式排除。
     */
    private val onUnauthorized: () -> Unit = {},
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

    /**
     * POST /api/login：用访问密码换会话 token。
     *
     * 三种响应都按后端契约处理：
     * - `200 {ok:true, token, cookieName}` → 登录成功
     * - `200 {ok:true, noPassword:true, token, ...}` → 服务端还没设密码，视为成功但要提示（见 noPassword）
     * - `401 {ok:false, error:"密码错误"}` → 凭证错误，走普通 Http 错误返回
     *
     * 密码不进任何日志：请求体用 JSONObject 拼好后直接交给传输层，
     * 全程没有把 password 插进异常信息、错误对象或字符串模板的地方。
     */
    suspend fun login(password: String, remember: Boolean): ApiResult<LoginSession> {
        if (!isConfigured) return ApiResult.Err(ApiError.InvalidConfig("baseUrl 为空"))
        // 刻意不在这里拦"密码为空"：服务端可能根本没设密码（noPassword 场景下随便填都能登录），
        // 客户端拦空会让这种服务器永远登不进去。由 UI 决定什么时候允许空提交，服务端决定对不对。

        val requestBody = JSONObject()
            .put(BODY_PASSWORD, password)
            .put(BODY_REMEMBER, remember)
            .toString()

        val raw = try {
            transport.post(
                url = baseUrl + API_PREFIX + ENDPOINT_LOGIN,
                body = requestBody,
                timeoutMs = requestTimeoutMs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }

        val session = when (val parsed = interpret(raw, { parseLoginSession(it, remember) }, isAuthAttempt = true)) {
            is ApiResult.Ok -> parsed.value
            is ApiResult.Err -> return parsed
        }
        // 防御：后端说成功却没给 token，等于拿到一个用不了的会话。
        // 当解析失败处理，比"登录成功然后下一次请求 401"好查得多。
        if (session.token.isBlank()) {
            return ApiResult.Err(ApiError.Parse("登录响应缺少 token", bodySnippet(raw.bodyText)))
        }
        return ApiResult.Ok(session)
    }

    /**
     * POST /api/logout：尽力通知服务端销毁会话。
     *
     * 刻意不走 [interpret]：登出失败（401/500/断网）都不该触发 onUnauthorized，
     * 也不该让本地登不出去 —— 调用方无论如何都会清本地 token，否则用户会被困在登录态里。
     */
    suspend fun logout(token: String): ApiResult<Unit> {
        if (!isConfigured) return ApiResult.Err(ApiError.InvalidConfig("baseUrl 为空"))
        if (token.isBlank()) return ApiResult.Ok(Unit)

        val raw = try {
            transport.post(
                url = baseUrl + API_PREFIX + ENDPOINT_LOGOUT,
                body = EMPTY_JSON_BODY,
                headers = mapOf(HEADER_AUTHORIZATION to BEARER_PREFIX + token),
                timeoutMs = requestTimeoutMs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }
        return if (raw.statusCode in HTTP_SUCCESS_RANGE) {
            ApiResult.Ok(Unit)
        } else {
            ApiResult.Err(ApiError.Http(raw.statusCode, ""))
        }
    }

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

    /**
     * @param isAuthAttempt 这次请求本身就是"验证凭证"（登录）。为 true 时 401 只当普通失败返回，
     *                      不触发 [onUnauthorized] —— 密码输错不该被当成"会话过期"去清别人的登录态。
     */
    private fun <T> interpret(
        raw: HttpRawResponse,
        parse: (JSONObject) -> T,
        isAuthAttempt: Boolean = false,
    ): ApiResult<T> {
        // 先判状态码：非 2xx 时 body 往往不是 JSON（或虽是 JSON 但只有 error），不值得再解析一遍。
        if (raw.statusCode !in HTTP_SUCCESS_RANGE) {
            val serverMessage = runCatching { extractServerError(JSONObject(raw.bodyText)) }
                .getOrDefault("")
            if (raw.statusCode == HTTP_UNAUTHORIZED && !isAuthAttempt) {
                // 凭证失效是全局事实：在这里统一通知上层清 token，各调用点不必各自判 401。
                onUnauthorized()
                return ApiResult.Err(ApiError.Http(HTTP_UNAUTHORIZED, serverMessage.ifBlank { MESSAGE_SESSION_EXPIRED }))
            }
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
