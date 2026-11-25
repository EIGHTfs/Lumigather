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

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
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
private const val ENDPOINT_FAVORITES = "/favorites"
private const val ENDPOINT_UPLOADED = "/uploaded"
private const val ENDPOINT_ZIP_SCAN = "/zip/scan"
private const val ENDPOINT_ZIP_IMG = "/zip/img"
private const val ENDPOINT_GIF = "/gif"
private const val ENDPOINT_ZIP_GIF = "/zip/gif"

// 压缩包/序列帧的参数名。注意这批接口**参数名不统一**（服务端实现就是这样）：
// zip/scan 与 gif 用 dir，zip/img 与 zip/gif 用 path；写错后端只会回 400，不会报"参数名错了"。
private const val QUERY_DIR = "dir"
private const val QUERY_PATH = "path"
private const val QUERY_FILE = "file"
private const val QUERY_DUR_MS = "dur_ms"
private const val QUERY_FPS = "fps"

// GIF 的默认帧率与"自动帧间隔"哨兵值放在 ArchiveModels.kt，客户端各处共用一份

// 收藏写操作的请求体字段名（与后端 readJsonBody 后取的键逐字一致）
private const val BODY_SRC = "src"
private const val BODY_NAME = "name"
private const val BODY_TITLE = "title"
private const val BODY_CAT = "cat"
private const val BODY_DIR = "dir"
private const val BODY_SIZE = "size"
private const val BODY_ROOT = "root"

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

        val session = when (val parsed = interpret(raw, isAuthAttempt = true) { body ->
            parseLoginSession(JSONObject(body), remember)
        }) {
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

    /**
     * GET /api/favorites —— 游客可看。
     *
     * 响应是**裸数组**（`[{...}]`），不是 `{items:[...]}` 包装，所以走数组通道解析。
     * 条目里的 src 在解析处就规范化（见 parseFavoriteEntry）。
     */
    suspend fun fetchFavorites(): ApiResult<List<FavoriteEntry>> =
        getJsonArray(ENDPOINT_FAVORITES) { array -> array.mapFavoriteEntries() }

    /**
     * POST /api/favorites —— 需登录（Bearer token）。
     *
     * 返回里带完整 favorites 列表：成功与"已存在"两种分支都直接用它替换本地列表，
     * 不用再加一次 GET。
     */
    suspend fun addFavorite(request: FavoriteRequest, token: String): ApiResult<FavoriteMutation> {
        val body = JSONObject().apply {
            put(BODY_SRC, MediaSrc.normalize(request.src))
            put(BODY_NAME, request.name)
            put(BODY_TITLE, request.title)
            put(BODY_CAT, request.cat)
            put(BODY_DIR, request.dir)
            put(BODY_SIZE, request.size)
            put(BODY_ROOT, request.root)
        }.toString()
        return writeJson(ENDPOINT_FAVORITES, body, token, isDelete = false, parse = ::parseFavoriteMutation)
    }

    /** DELETE /api/favorites —— 需登录；后端从请求体里读 `{src}`（不是路径参数）。 */
    suspend fun removeFavorite(src: String, token: String): ApiResult<List<FavoriteEntry>> {
        val body = JSONObject().put(BODY_SRC, MediaSrc.normalize(src)).toString()
        // 数组缺失时会返回空列表：服务端异常降级也不该让客户端崩
        return writeJson(ENDPOINT_FAVORITES, body, token, isDelete = true, parse = ::parseFavoritesField)
    }

    /**
     * GET /api/zip/scan?dir= —— 列出目录下的压缩包及包内图片。
     *
     * 参数名是 dir（不是 root/path）：这三套接口的参数名各写各的，见 QUERY_* 常量的注释。
     */
    suspend fun scanArchives(dir: String): ApiResult<List<ArchiveEntry>> {
        if (dir.isBlank()) return ApiResult.Err(ApiError.BadArgument("scanArchives 需要非空目录"))
        return getJson(
            ENDPOINT_ZIP_SCAN,
            mapOf(QUERY_DIR to dir),
            ::parseArchiveList,
        )
    }

    /**
     * 包内单张图片的绝对 URL（服务端已按 flip-rule 决定要不要 180° 翻转）。
     *
     * 条目名在这里做 RFC 3986 编码：包里常有中文、空格、`#`、`&`，不编码会被服务端当成别的条目。
     * 含 `..` 的条目名返回空串（服务端会拒；客户端不把必然失败的图塞进网格）。
     */
    fun zipImageUrl(zipPath: String, entryName: String): String {
        if (!ArchiveHeuristics.isSafeEntryName(entryName)) return ""
        return UrlBuilder.build(
            baseUrl + API_PREFIX + ENDPOINT_ZIP_IMG,
            mapOf(QUERY_PATH to zipPath, QUERY_FILE to entryName),
        )
    }

    /** 目录序列帧 → GIF 的绝对 URL。帧间隔交给服务端算（dur_ms=0）。 */
    fun directoryGifUrl(dir: String, durationMs: Int = GIF_AUTO_DURATION_MS): String =
        UrlBuilder.build(
            baseUrl + API_PREFIX + ENDPOINT_GIF,
            mapOf(QUERY_DIR to dir, QUERY_DUR_MS to durationMs.toString()),
        )

    /**
     * 包内序列帧 → GIF 的绝对 URL。
     *
     * fps 只有"用 ini 的 $speed 换算帧间隔"时服务端才用；这里显式传默认值，
     * 让请求参数自解释（客户端不做任何换算）。
     */
    fun zipGifUrl(
        zipPath: String,
        durationMs: Int = GIF_AUTO_DURATION_MS,
        fps: Int = DEFAULT_GIF_FPS,
    ): String = UrlBuilder.build(
        baseUrl + API_PREFIX + ENDPOINT_ZIP_GIF,
        mapOf(
            QUERY_PATH to zipPath,
            QUERY_DUR_MS to durationMs.toString(),
            QUERY_FPS to fps.toString(),
        ),
    )

    /**
     * 取一次二进制 URL 的失败说明（只在失败时调用）。
     *
     * 为什么需要它：GIF 是交给图片加载器按 URL 拉的，加载失败时只知道"失败了"，
     * 拿不到服务端 400 里那句中文（如「目录无效」）。失败后用同一个 URL 再问一次，
     * 服务端回 400 是**不编码 GIF** 的（快速失败），所以这次请求很便宜。
     *
     * @return 可直接上屏的中文说明；拿不到就回退成服务端原文或空串
     */
    suspend fun fetchFailureText(url: String): String {
        val response = try {
            transport.getBytes(url, emptyMap(), requestTimeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return describeFailure(failure)
        }
        val bodyText = response.bytes.toString(Charsets.UTF_8).trim()
        // 400 的 body 有两种：JSON {ok:false,error:"目录无效"} 或纯文本 "bad request"
        val fromJson = runCatching { extractServerError(JSONObject(bodyText)) }.getOrDefault("")
        return fromJson.ifBlank { bodyText }
    }

    /** GET /api/uploaded —— 游客可看；同样是裸数组。 */
    suspend fun fetchUploaded(): ApiResult<List<UploadedEntry>> =
        getJsonArray(ENDPOINT_UPLOADED) { array -> array.mapUploadedEntries() }

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
        return interpret(raw) { body -> parse(JSONObject(body)) }
    }

    // 数组响应通道（favorites / uploaded 都是裸数组，不是 {items:[...]} 包装）
    private suspend fun <T> getJsonArray(
        endpoint: String,
        parse: (JSONArray) -> T,
    ): ApiResult<T> {
        if (!isConfigured) return ApiResult.Err(ApiError.InvalidConfig("baseUrl 为空"))

        val url = baseUrl + API_PREFIX + endpoint
        val raw = try {
            transport.get(url, emptyMap(), requestTimeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }
        return interpret(raw) { body -> parse(JSONArray(body)) }
    }

    /**
     * 写请求通道（收藏的增删）：POST/DELETE + Bearer token + 对象响应。
     *
     * token 为空时仍然发出去：让服务端用 401 回答，比在客户端猜"算不算登录"更可靠 ——
     * 服务端才是会话有效性的唯一裁判（本地 token 可能已过期但还没被发现）。
     */
    private suspend fun <T> writeJson(
        endpoint: String,
        body: String,
        token: String,
        isDelete: Boolean,
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        if (!isConfigured) return ApiResult.Err(ApiError.InvalidConfig("baseUrl 为空"))

        val url = baseUrl + API_PREFIX + endpoint
        val headers = if (token.isBlank()) {
            emptyMap()
        } else {
            mapOf(HEADER_AUTHORIZATION to BEARER_PREFIX + token)
        }
        val raw = try {
            if (isDelete) {
                transport.delete(url, body, headers = headers, timeoutMs = requestTimeoutMs)
            } else {
                transport.post(url, body, headers = headers, timeoutMs = requestTimeoutMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }
        return interpret(raw) { text -> parse(JSONObject(text)) }
    }

    /**
     * @param isAuthAttempt 这次请求本身就是"验证凭证"（登录）。为 true 时 401 只当普通失败返回，
     *                      不触发 [onUnauthorized] —— 密码输错不该被当成"会话过期"去清别人的登录态。
     */
    /**
     * 统一的"状态码 + 响应体"解释器。
     *
     * 用 [decode] 而不是直接收 JSONObject：收藏与游客上传的响应是**裸数组**，
     * 与其它接口的对象响应只差最后一步解码 —— 把差异收进一个 lambda，
     * 状态码判定、401 回调、200 包 error 的处理就都只有一份（不会出现"对象版判了 401、数组版忘了"）。
     *
     * @param isAuthAttempt 这次请求本身就是"验证凭证"（登录）。为 true 时 401 只当普通失败返回，
     *                      不触发 [onUnauthorized] —— 密码输错不该被当成"会话过期"去清别人的登录态。
     */
    private fun <T> interpret(
        raw: HttpRawResponse,
        isAuthAttempt: Boolean = false,
        decode: (String) -> T,
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

        // 后端存在"200 + {"error": ...}"的路径（实测：root 指向不存在路径时，某些前缀下走这条）。
        // 不抽这个字段就会把一次失败当成"空目录"成功返回，UI 表现为"分类是空的"而没有任何错误提示。
        // 数组响应解析成 JSONObject 会抛异常，runCatching 直接给空串 —— 正好不误判。
        val serverError = runCatching { extractServerError(JSONObject(raw.bodyText)) }.getOrDefault("")
        if (serverError.isNotBlank()) {
            return ApiResult.Err(ApiError.Http(raw.statusCode, serverError))
        }

        return try {
            ApiResult.Ok(decode(raw.bodyText))
        } catch (failure: Throwable) {
            ApiResult.Err(ApiError.Parse(describeFailure(failure), bodySnippet(raw.bodyText)))
        }
    }

    // 截断 body 片段：只保留开头，避免把含敏感字段的完整响应写进日志。
    private fun bodySnippet(body: String): String =
        if (body.length <= ERROR_BODY_SNIPPET_LENGTH) body else body.take(ERROR_BODY_SNIPPET_LENGTH)
}
