// 仓库层：把"接口"翻译成"业务语义"。UI 只跟这一层打交道。
//
// 这一层解决的三个具体问题：
// 1) 分类与路径的合并。后端把分类名（categories）和分类的绝对路径（directories）分成两个数组下发，
//    UI 不该自己配对。这里合成 [CategoryView]，并且把"某分类没有配路径"这种脏数据显式标出来
//    （[CategoryView.rootIsFallback]），而不是悄悄回落到全局根 —— 悄悄回落的表现就是
//    "所有分类看到同一批图"。
// 2) 图片地址已经物化。[ImageItem.absoluteUrl] 在这里填好，UI 拿到就能直接加载。
// 3) 张数单独取。分类列表能一次拿到，但每个分类的图片张数要各自请求一次
//    （实测 8 个分类串行合计约 14 秒），所以首屏先出列表、张数后台补齐 —— 见 refreshCategoryCounts。

package com.eightfs.lumigather.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// 补张数时的并发上限。服务端每个请求都要扫盘（实测最慢 3.4 秒），
// 8 个并发会把 NAS 的盘和本机内存一起压上去，3 个是"能明显变快又不打爆"的折中。
const val DEFAULT_COUNT_CONCURRENCY = 3

// 取张数只需要总数，不取列表：limit=1 让后端只序列化一张图，省下传输与解析。
private const val COUNT_PROBE_LIMIT = 1

/**
 * 补张数时 UI 用不到这个枚举的原始值，它存在的意义是"张数还没算出来"和"算出 0 张"必须能区分：
 * 前者要显示占位符，后者要显示 0。
 */
enum class CountState { UNKNOWN, LOADING, READY, FAILED }

/**
 * 分类视图模型 —— 这是 T4/T5/T6 对接的唯一分类结构。
 *
 * 前四个字段是跨成员契约里约定的最小集合：name / color / rootPath / imageCount。
 * [rootPath] 是"这个分类的图在哪个绝对路径下"，请求图片时原样作为 root 参数传回。
 */
data class CategoryView(
    val name: String,
    val color: String,
    val rootPath: String,
    val imageCount: Int,
    val label: String = name,
    val countState: CountState = CountState.UNKNOWN,
    val rootIsFallback: Boolean = false
)

/**
 * 分类 + 图片的读取入口。
 *
 * 内部缓存最近一次站点配置与分类视图：分类的 rootPath 只解析一次，切换分类时直接用，
 * 不必每翻一页都重新拉 /api/lumigather。
 */
class LumigatherRepository(private val api: ApiClient) {

    private var cachedConfig: SiteConfig? = null
    private var cachedCategories: List<CategoryView> = emptyList()

    // 站点标题："拾光集"，顶栏与底部状态栏展示用。
    val title: String
        get() = cachedConfig?.title.orEmpty()

    // 最近一次成功解析的分类视图，按后端下发顺序（与设计稿的 8 个分类顺序一致）。
    fun categories(): List<CategoryView> = cachedCategories

    fun findCategory(categoryName: String): CategoryView? =
        cachedCategories.firstOrNull { it.name == categoryName }

    // GET /api/status：底部状态栏的"在线 · 版本 · 端口"。
    suspend fun loadStatus(): ApiResult<ServerStatus> = api.fetchStatus()

    // 登录/登出：仓库只做转发，token 的保存与清理由状态层负责（它才知道"当前是哪台服务器"）。
    suspend fun login(password: String, remember: Boolean): ApiResult<LoginSession> =
        api.login(password, remember)

    suspend fun logout(token: String): ApiResult<Unit> = api.logout(token)

    // 收藏与游客上传：仓库只做转发 + 地址解析，收藏的"该不该存"判断在状态层。
    suspend fun loadFavorites(): ApiResult<List<FavoriteEntry>> = api.fetchFavorites()

    suspend fun addFavorite(request: FavoriteRequest, token: String): ApiResult<FavoriteMutation> =
        api.addFavorite(request, token)

    suspend fun removeFavorite(src: String, token: String): ApiResult<List<FavoriteEntry>> =
        api.removeFavorite(src, token)

    suspend fun loadUploaded(): ApiResult<List<UploadedEntry>> = api.fetchUploaded()

    // 目录层级：按需加载单层（本层图片 + 子目录统计）
    suspend fun loadDir(root: String, path: String): ApiResult<DirPage> = api.fetchDir(root, path)

    // 压缩包与序列帧：仓库只做转发，客户端不做任何解压/编码/ini 解析。
    suspend fun scanArchives(dir: String): ApiResult<List<ArchiveEntry>> = api.scanArchives(dir)

    fun zipImageUrl(zipPath: String, entryName: String): String = api.zipImageUrl(zipPath, entryName)

    fun directoryGifUrl(dir: String): String = api.directoryGifUrl(dir)

    fun zipGifUrl(zipPath: String): String = api.zipGifUrl(zipPath)

    // GIF 拉取失败时再问一次原因（服务端 400 会带中文 error）
    suspend fun gifFailureText(url: String): String = api.fetchFailureText(url)

    // 把相对媒体地址拼成绝对地址。收藏条目只有相对 src，而网格/详情栏要的是可直接加载的绝对地址 ——
    // 拼接规则（baseUrl 归一化、子路径前缀、不二次编码）只在 ApiClient 里有一份，这里只是暴露出来。
    fun resolveMediaUrl(relativeOrAbsolute: String): String = api.resolveUrl(relativeOrAbsolute)

    /**
     * 拉分类列表（含每个分类的绝对路径），不含张数。
     *
     * 张数故意不在这里取：8 个分类各要一次扫盘请求，串行约 14 秒，塞进首屏等于白屏 14 秒。
     * 首屏拿到 8 个分类即可渲染，张数交给 [refreshCategoryCounts] 后台补。
     */
    suspend fun loadCategories(): ApiResult<List<CategoryView>> {
        val config = when (val result = api.fetchSiteConfig()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> return ApiResult.Err(result.error)
        }
        val views = buildCategoryViews(config)
        cachedConfig = config
        cachedCategories = views
        return ApiResult.Ok(views)
    }

    /**
     * 补单个分类的张数：用 limit=1 的请求读 total。
     *
     * 必须带该分类自己的 rootPath。不带 root 时后端回落到全局 fsRoot，
     * 8 个分类会返回同一个数字 —— 这就是"张数全都一样"的根因。
     */
    suspend fun refreshCategoryCount(categoryName: String): ApiResult<CategoryView> {
        val current = cachedCategories.firstOrNull { it.name == categoryName }
            ?: return ApiResult.Err(ApiError.BadArgument("未知分类：$categoryName"))

        val probe = probeCategoryCount(current)
        replaceCategories(listOf(probe.view))
        return when (val error = probe.error) {
            null -> ApiResult.Ok(probe.view)
            else -> ApiResult.Err(error)
        }
    }

    /**
     * 批量补张数，并发上限 [maxConcurrent]，每完成一个就回调 [onCount] 一次。
     *
     * 关键实现细节：**并发阶段只探测、不写缓存**，全部结果回来后再一次性合并。
     * 如果让每个协程自己 `cachedCategories = cachedCategories.map{...}`，两个协程会各自读到旧列表、
     * 各自写回自己那一份，后写的把先写的覆盖掉 —— 表现是部分分类张数一直是 0/旧值，
     * 而且因为接口全 200，日志里什么都看不出来。合并放到单线程后就没有这个竞争窗口。
     *
     * [onCount] 在协程池线程上被调用（不保证主线程），UI 侧要自己切回主线程再刷状态。
     */
    suspend fun refreshCategoryCounts(
        categoryNames: List<String> = cachedCategories.map { it.name },
        maxConcurrent: Int = DEFAULT_COUNT_CONCURRENCY,
        onCount: (CategoryView) -> Unit = {}
    ): List<CategoryView> {
        if (categoryNames.isEmpty()) return cachedCategories

        val targets = categoryNames.mapNotNull { name -> cachedCategories.firstOrNull { it.name == name } }
        if (targets.isEmpty()) return cachedCategories

        val concurrency = maxConcurrent.coerceAtLeast(1)
        val probes = ArrayList<CountProbe>(targets.size)
        // 按 chunk 分组而不是一把全并发：让"最多同时几个请求"是显式的、可推理的。
        for (chunk in targets.chunked(concurrency)) {
            coroutineScope {
                val chunkProbes = chunk.map { view -> async { probeCategoryCount(view) } }.awaitAll()
                probes.addAll(chunkProbes)
            }
        }

        // 合并缓存：这里只在一处写，前面并发读到的都是同一份旧列表，不会互相覆盖。
        replaceCategories(probes.map { it.view })
        probes.forEach { probe -> if (probe.error == null) onCount(probe.view) }
        return cachedCategories
    }

    /**
     * 取某一页图片，并把每张图的绝对地址填好。
     *
     * [rootPath] 用分类的 rootPath（或目录浏览时的当前路径），[offset] 是已加载条数，
     * [recursive] 默认 true 表示连子目录一起取。
     */
    suspend fun loadPage(
        rootPath: String,
        offset: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        recursive: Boolean = true
    ): ApiResult<PagedImages> {
        val result = api.fetchImages(rootPath, offset, limit, recursive)
        return when (result) {
            is ApiResult.Ok -> ApiResult.Ok(withAbsoluteUrls(result.value))
            is ApiResult.Err -> ApiResult.Err(result.error)
        }
    }

    // 按分类名取一页，省得 UI 自己查 rootPath。分类未加载时给 BadArgument，提示先 loadCategories。
    suspend fun loadPageForCategory(
        categoryName: String,
        offset: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        recursive: Boolean = true
    ): ApiResult<PagedImages> {
        val category = findCategory(categoryName)
            ?: return ApiResult.Err(ApiError.BadArgument("分类尚未加载：$categoryName"))
        return loadPage(category.rootPath, offset, limit, recursive)
    }

    // 目录浏览（次级页面用）：列某一级下的子目录名字。
    suspend fun loadDirectory(rootPath: String): ApiResult<DirectoryListing> =
        api.fetchDirectories(rootPath)

    // 单张图的绝对地址。模型里通常已经填好，这里给"只有 relativeUrl 的旧对象"兜底。
    fun mediaUrl(item: ImageItem): String = api.mediaUrl(item)

    // 把服务端相对地址物化成绝对地址：UI 不必也不应该自己拼。
    private fun withAbsoluteUrls(page: PagedImages): PagedImages =
        page.copy(images = page.images.map { it.copy(absoluteUrl = api.resolveUrl(it.relativeUrl)) })

    // 分类名 → 绝对路径。用 associate 而不是逐项查找：8 项规模下差异可忽略，但语义更清楚。
    // 同名分类重复配置时后一条覆盖前一条（后端配置问题，取最后一个更符合"后来改的生效"直觉）。
    private fun buildCategoryViews(config: SiteConfig): List<CategoryView> {
        val pathByCategory = config.directories
            .filter { it.category.isNotBlank() && it.path.isNotBlank() }
            .associate { it.category to it.path }

        return config.categories.map { category ->
            val matchedPath = pathByCategory[category.name]
            CategoryView(
                name = category.name,
                label = category.label.ifBlank { category.name },
                color = category.color,
                // 没有配路径时回落到全局根，同时用 rootIsFallback 把这个事实暴露给 UI，
                // 避免"看着正常、其实全是同一个目录的图"这种静默错误。
                rootPath = matchedPath ?: config.fsRoot,
                imageCount = 0,
                countState = CountState.UNKNOWN,
                rootIsFallback = matchedPath == null
            )
        }
    }

    private fun replaceCategories(updatedViews: List<CategoryView>) {
        if (updatedViews.isEmpty()) return
        // 一次性按 name 建索引再映射，避免嵌套线性查找；同时也是"单点写入"，
        // 保证并发探测阶段不会碰这个字段（见 refreshCategoryCounts 的说明）。
        val byName = updatedViews.associateBy { it.name }
        cachedCategories = cachedCategories.map { existing -> byName[existing.name] ?: existing }
    }

    /**
     * 纯探测：只发一次 limit=1 的请求读 total，不写任何缓存。
     *
     * 拆成"探测（可并发）+ 合并（单点写）"两步，是为了让并发补张数没有丢更新的窗口。
     * [CountProbe.error] 非空时 [CountProbe.view] 已经被标成 FAILED，调用方据此画失败态。
     */
    private suspend fun probeCategoryCount(current: CategoryView): CountProbe {
        if (current.rootPath.isBlank()) {
            return CountProbe(
                view = current.copy(countState = CountState.FAILED),
                error = ApiError.InvalidConfig("分类 ${current.name} 没有可用路径")
            )
        }
        val result = api.fetchImages(
            root = current.rootPath,
            offset = 0,
            limit = COUNT_PROBE_LIMIT,
            recursive = true
        )
        return when (result) {
            is ApiResult.Ok -> CountProbe(
                view = current.copy(imageCount = result.value.total, countState = CountState.READY),
                error = null
            )
            is ApiResult.Err -> CountProbe(
                view = current.copy(countState = CountState.FAILED),
                error = result.error
            )
        }
    }
}

// 一次张数探测的结果：更新后的视图 + 失败原因（成功时为 null）。
// 用私有小结构而不是 Pair，是为了让调用点读起来是 probe.error / probe.view 而不是 second/first。
private class CountProbe(val view: CategoryView, val error: ApiError?)
