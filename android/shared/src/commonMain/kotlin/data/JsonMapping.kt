// JSON → 模型的映射层。只做"取值 + 兜默认值"，不含任何业务判断。
//
// 用 Kuikly core 自带的 JSONObject 而不是引入 kotlinx.serialization：core 的 Android 实现
// 底层就是 Kotlin LinkedHashMap + 自带 JSONTokener（不是 org.json），所以在普通 JVM 单测里
// 能直接跑（org.json 在 android.jar 里是抛 "not mocked" 的桩）。于是解析逻辑可离线覆盖，
// 也不必为一个 JSON 解析引入编译器插件。
//
// 每个字段都用 optXxx(name, default)：后端存在缺字段的降级响应，缺字段要变成"空值"，
// 而不是 JSONException。

package com.eightfs.lumigather.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

// 后端字段名集中定义：同一批字段名在多个解析函数里复用，写错一处就会静默变成默认值
// （比如把 returned 拼成 returns，永远是 0，分页会一直以为到底了），集中定义便于核对。
private object Field {
    const val TITLE = "title"
    const val FS_ROOT = "fsRoot"
    const val FS_ROOTS = "fsRoots"
    const val IMAGES_PER_ROW = "imagesPerRow"
    const val CATEGORIES = "categories"
    const val DIRECTORIES = "directories"

    const val NAME = "name"
    const val LABEL = "label"
    const val COLOR = "color"
    const val CATEGORY = "category"

    const val PATH = "path"
    const val ROOT = "root"
    const val ITEMS = "items"
    const val LIMIT = "limit"
    const val CLAMPED = "clamped"
    const val CAN_UP = "canUp"

    const val RECURSIVE = "recursive"
    const val COUNT = "count"
    const val TOTAL = "total"
    const val OFFSET = "offset"
    const val RETURNED = "returned"
    const val IMAGES = "images"
    const val URL = "url"
    const val SIZE = "size"
    const val MODIFIED = "modified"

    const val OK = "ok"
    const val NEEDS_SETUP = "needsSetup"
    const val NEEDS_AUTH = "needsAuth"
    const val AUTHED = "authed"
    const val PORT = "port"
    const val UPTIME_SEC = "uptimeSec"
    const val VERSION = "version"

    // 后端在部分失败路径上会回 {"error": "..."}，见 ApiClient 的 200 包装错误处理。
    const val ERROR = "error"

    // 登录响应：{ok, token, cookieName, noPassword?, message?}
    const val TOKEN = "token"
    const val COOKIE_NAME = "cookieName"
    const val NO_PASSWORD = "noPassword"
    const val MESSAGE = "message"

    // 收藏 / 游客上传：条目字段 + 写操作的响应包装（TITLE / NAME / SIZE / URL / ROOT 复用上面的定义）
    const val SRC = "src"
    const val CAT = "cat"
    const val DIR = "dir"
    const val ADDED_AT = "addedAt"
    const val ALREADY_EXISTS = "alreadyExists"
    const val FAVORITES = "favorites"
    const val ID = "id"
}

internal fun parseSiteConfig(json: JSONObject): SiteConfig = SiteConfig(
    title = json.optString(Field.TITLE, ""),
    fsRoot = json.optString(Field.FS_ROOT, ""),
    fsRoots = json.optJSONArray(Field.FS_ROOTS).toStringList(),
    imagesPerRow = json.optInt(Field.IMAGES_PER_ROW, DEFAULT_PAGE_SIZE),
    categories = json.optJSONArray(Field.CATEGORIES).mapObjects(::parseCategory),
    directories = json.optJSONArray(Field.DIRECTORIES).mapObjects(::parseDirectoryRef)
)

private fun parseCategory(json: JSONObject): Category = Category(
    name = json.optString(Field.NAME, ""),
    // label 缺省时回退成 name：UI 一律读 label，不必自己判空。
    label = json.optString(Field.LABEL, "").ifBlank { json.optString(Field.NAME, "") },
    color = json.optString(Field.COLOR, "")
)

private fun parseDirectoryRef(json: JSONObject): DirectoryRef = DirectoryRef(
    path = json.optString(Field.PATH, ""),
    category = json.optString(Field.CATEGORY, "")
)

internal fun parseDirectoryListing(json: JSONObject): DirectoryListing = DirectoryListing(
    root = json.optString(Field.ROOT, ""),
    items = json.optJSONArray(Field.ITEMS).toStringList(),
    // 后端这个字段叫 limit，值是路径字符串。照抄名字会误导成"每页条数"。
    limitPath = json.optString(Field.LIMIT, ""),
    clamped = json.optBoolean(Field.CLAMPED, false),
    canUp = json.optBoolean(Field.CAN_UP, false)
)

internal fun parsePagedImages(json: JSONObject): PagedImages = PagedImages(
    path = json.optString(Field.PATH, ""),
    root = json.optString(Field.ROOT, ""),
    // 递归开关缺省按 true：本 App 一律按分类递归取图，默认值与调用方保持一致。
    recursive = json.optBoolean(Field.RECURSIVE, true),
    count = json.optInt(Field.COUNT, 0),
    total = json.optInt(Field.TOTAL, 0),
    offset = json.optInt(Field.OFFSET, 0),
    limit = json.optInt(Field.LIMIT, DEFAULT_PAGE_SIZE),
    returned = json.optInt(Field.RETURNED, 0),
    images = json.optJSONArray(Field.IMAGES).mapObjects(::parseImageItem)
)

private fun parseImageItem(json: JSONObject): ImageItem = ImageItem(
    name = json.optString(Field.NAME, ""),
    path = json.optString(Field.PATH, ""),
    relativeUrl = json.optString(Field.URL, ""),
    sizeText = json.optString(Field.SIZE, ""),
    modifiedIso = json.optString(Field.MODIFIED, "")
)

internal fun parseServerStatus(json: JSONObject): ServerStatus = ServerStatus(
    ok = json.optBoolean(Field.OK, false),
    needsSetup = json.optBoolean(Field.NEEDS_SETUP, false),
    needsAuth = json.optBoolean(Field.NEEDS_AUTH, false),
    authed = json.optBoolean(Field.AUTHED, false),
    port = json.optInt(Field.PORT, 0),
    uptimeSec = json.optLong(Field.UPTIME_SEC, 0L),
    version = json.optString(Field.VERSION, "")
)

// 顶层 {"error": "..."} 的抽取：失败路径也要能拿到服务端给的原因（如 ENOENT 原文）供日志。
internal fun extractServerError(json: JSONObject): String = json.optString(Field.ERROR, "")

/**
 * 收藏条目 → 模型。`src` 在解析处就规范化：比对与去重全靠它，保留原始写法会让
 * "http://host:port/api/media?..." 与 "/api/media?..." 被当成两张图。
 */
internal fun parseFavoriteEntry(json: JSONObject): FavoriteEntry = FavoriteEntry(
    src = MediaSrc.normalize(json.optString(Field.SRC, "")),
    name = json.optString(Field.NAME, ""),
    title = json.optString(Field.TITLE, ""),
    cat = json.optString(Field.CAT, ""),
    dir = json.optString(Field.DIR, ""),
    size = json.optString(Field.SIZE, ""),
    root = json.optString(Field.ROOT, ""),
    addedAt = json.optLong(Field.ADDED_AT, 0L),
)

/** 游客上传条目 → 模型。同样规范化 src（服务端下发的是 /uploads/xxx 相对地址）。 */
internal fun parseUploadedEntry(json: JSONObject): UploadedEntry = UploadedEntry(
    id = json.optString(Field.ID, ""),
    src = MediaSrc.normalize(json.optString(Field.SRC, "")),
    url = json.optString(Field.URL, ""),
    name = json.optString(Field.NAME, ""),
    title = json.optString(Field.TITLE, ""),
)

/**
 * 添加收藏的响应：`{ok, alreadyExists?, favorites:[...]}`。
 *
 * 两个分支都要读 favorites：服务端在"已存在"时同样回传完整列表，
 * 客户端直接用它替换本地列表，比再加一次 GET 省一个来回。
 */
internal fun parseFavoriteMutation(json: JSONObject): FavoriteMutation = FavoriteMutation(
    alreadyExists = json.optBoolean(Field.ALREADY_EXISTS, false),
    favorites = json.optJSONArray(Field.FAVORITES).mapFavoriteEntries(),
)

// 写操作响应里的 favorites 数组。ApiClient 需要它但 Field 是文件私有的，
// 所以在这里给一个小出口，而不是把 Field 提升为 internal（那会让字段名常量四处可用）。
internal fun parseFavoritesField(json: JSONObject): List<FavoriteEntry> =
    json.optJSONArray(Field.FAVORITES).mapFavoriteEntries()

// 收藏数组（GET 的裸数组，或写操作响应里的 favorites 字段）
internal fun JSONArray?.mapFavoriteEntries(): List<FavoriteEntry> = mapObjects(::parseFavoriteEntry)

// 游客上传数组（GET /api/uploaded 的裸数组）
internal fun JSONArray?.mapUploadedEntries(): List<UploadedEntry> = mapObjects(::parseUploadedEntry)

/**
 * 登录响应 → 会话模型。
 *
 * [remember] 取的是"这次请求带的参数"而不是响应字段：服务端不回显它，
 * 而客户端的有效期推算必须与这次请求一致（勾了就 30 天、没勾就 3 天）。
 */
internal fun parseLoginSession(json: JSONObject, remember: Boolean): LoginSession = LoginSession(
    token = json.optString(Field.TOKEN, ""),
    cookieName = json.optString(Field.COOKIE_NAME, ""),
    noPassword = json.optBoolean(Field.NO_PASSWORD, false),
    message = json.optString(Field.MESSAGE, ""),
    remember = remember,
)

// 数组缺失（null）时返回空列表：降级响应里整个数组都可能不存在。
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        result.add(optString(index, "") ?: "")
    }
    return result
}

// 对象数组映射：跳过非对象元素（脏数据不该让整页解析失败）。
private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val result = ArrayList<T>(length())
    for (index in 0 until length()) {
        val element = optJSONObject(index) ?: continue
        result.add(transform(element))
    }
    return result
}
