// 浏览子状态机：分类列表、图片分页、切换分类、重试，以及"服务端现在通不通"这条状态线上的一切。
//
// 它是这次拆分里最大的一块，因为"看图"本来就是主流程。依赖依然只有状态通道 + 一个仓库 provider；
// 三处跨功能的动作通过回调交出去，而不是直接调用别的子状态机：
//   - [onServerNeedsSetup]：服务器是否设过密码（登录态归登录子状态机管）；
//   - [onServerReady]：能拉到分类 = 这台服务器可用了（设置态归设置子状态机管）；
//   - [onNeedsBootstrap]：连仓库都没有（地址未配置）时，整条启动链路该重跑（facade 管地址）。
//
// 到底判据沿用数据层的 PagedImages.isEnd：越界请求时后端会回 returned=0 但 total 仍是真实值，
// 自己拿 total 算很容易多请求一次空页。

package ui.screens.state

import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.DirPage
import com.eightfs.lumigather.data.DEFAULT_COUNT_CONCURRENCY
import com.eightfs.lumigather.data.DEFAULT_PAGE_SIZE
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.PagedImages

class BrowseController(
    private val channel: StateChannel,
    private val repositoryProvider: () -> LumigatherRepository?,
    private val onNeedsBootstrap: suspend () -> Unit,
    private val onServerNeedsSetup: (needsSetup: Boolean) -> Unit,
    private val onServerReady: () -> Unit,
) {

    /**
     * 目录栈：只在本类内部存在，**不暴露给 UI**。
     *
     * 界面需要的信息只有两样：面包屑文本、能不能返回上级 —— 这两个由 [publishBreadcrumbs] 写进状态。
     * 把栈本身放进状态会让页面有能力"改栈"，那正是层级导航最容易出 bug 的地方（栈与显示不同步）。
     */
    private val pathStack: MutableList<String> = mutableListOf()

    /**
     * 主链路：服务端状态 → 分类列表 → 第一个分类的第一页 → 后台补各分类张数。
     *
     * 张数统计放最后且不阻塞首屏：8 个分类各要扫一次盘（实测冷启动合计 25 秒），
     * 等它等于白屏 25 秒。
     */
    suspend fun refreshContent() {
        val activeRepository = repositoryProvider() ?: return
        channel.mutate { it.copy(isLoadingCategories = true, categoryError = null) }
        refreshServerStatus(activeRepository)

        when (val result = activeRepository.loadCategories()) {
            is ApiResult.Err -> channel.mutate {
                it.copy(isLoadingCategories = false, categoryError = result.error)
            }

            is ApiResult.Ok -> {
                val categories = result.value
                val firstCategory = categories.firstOrNull()
                channel.mutate {
                    it.copy(
                        isLoadingCategories = false,
                        categories = categories,
                        serverTitle = activeRepository.title,
                        // 拉到分类就默认看第一个：筛选值是"分类"这一支，界面立刻有内容可看
                        filter = ContentFilter.Category(firstCategory?.name.orEmpty()),
                    )
                }
                // 能拉到分类说明这台服务器可用：让设置态知道"现在有地址可退回了"
                onServerReady()
                if (firstCategory == null) return
                loadCategory(firstCategory.name)
                fillCategoryCounts(categories.map { it.name })
            }
        }
    }

    /**
     * 加载某个分类的第一页。
     *
     * 这里**不再设置筛选值**：筛选值只有一个写入口（facade 的 selectFilter），
     * 浏览子状态机只负责"把某个分类的图拉回来"。这样就不存在"改了分类却忘了改视图"的可能 ——
     * 那不是靠这里补一句 `filter = ...` 解决的，而是靠"不允许第二个地方写它"。
     */
    suspend fun loadCategory(categoryName: String) {
        // 切分类 = 回到该分类根目录：栈必须清空，否则会带着上一个分类的层级
        pathStack.clear()
        loadCurrentDir(categoryName)
    }

    /** 进入子文件夹（name 是目录名，层级由栈累积成相对路径）。 */
    suspend fun enterFolder(name: String) {
        val categoryName = channel.state.currentCategoryName ?: return
        pathStack.add(name)
        loadCurrentDir(categoryName)
    }

    /** 返回上级：栈空时什么都不做（根目录没有上级）。 */
    suspend fun goUp() {
        val categoryName = channel.state.currentCategoryName ?: return
        if (pathStack.isEmpty()) return
        pathStack.removeAt(pathStack.lastIndex)
        loadCurrentDir(categoryName)
    }

    /**
     * 跳回面包屑第 index 段（0 = 分类根）。
     *
     * 语义是"截断"而不是"再进一次"：从 A/B/C 点 A 段应得到 A，而不是把 A 压到 C 后面。
     */
    suspend fun jumpTo(index: Int) {
        val categoryName = channel.state.currentCategoryName ?: return
        val targetDepth = index.coerceAtLeast(0)
        while (pathStack.size > targetDepth) {
            pathStack.removeAt(pathStack.lastIndex)
        }
        loadCurrentDir(categoryName)
    }

    /** 当前层相对分类根的路径（""=根），给 facade 拼压缩包扫描的绝对路径用。 */
    fun currentRelativePath(): String = pathStack.joinToString("/")

    /** 触底加载下一页。 */
    suspend fun loadMore() {
        val current = channel.state
        val activeRepository = repositoryProvider() ?: return
        if (current.isLoadingMore || current.isLoadingPage || !current.hasMore) return
        val categoryName = current.currentCategoryName ?: return

        channel.mutate { it.copy(isLoadingMore = true) }
        when (val result = activeRepository.loadPageForCategory(
            categoryName = categoryName,
            offset = current.loadedCount,
            limit = DEFAULT_PAGE_SIZE,
        )) {
            is ApiResult.Ok -> applyPage(result.value, append = true)
            is ApiResult.Err -> channel.mutate { it.copy(isLoadingMore = false, pageError = result.error) }
        }
    }

    // 重试：连仓库都没有就整条启动链路重来；分类都没拿到就重跑主链路；只是图片页失败就只重拉当前页。
    suspend fun retry() {
        if (repositoryProvider() == null) {
            onNeedsBootstrap()
            return
        }
        val current = channel.state
        val categoryName = current.currentCategoryName
        if (current.categories.isEmpty() || categoryName == null) {
            refreshContent()
            return
        }
        loadCurrentDir(categoryName)
    }

    /**
     * 清空"属于旧服务器"的浏览数据（换地址时由 facade 调用）。
     *
     * 不清的话，用户会以为新地址下也是这些图；而 isLoadingCategories=true 是为了让界面
     * 立刻回到"正在读取分类"的加载态，而不是闪一下旧数据的空壳。
     */
    fun resetContent() {
        pathStack.clear()
        channel.mutate {
            it.copy(
                categories = emptyList(),
                images = emptyList(),
                dirEntries = emptyList(),
                breadcrumbs = emptyList(),
                canGoUp = false,
                archiveImages = emptyList(),
                expandedEntryKey = null,
                filter = ContentFilter.Category(""),
                categoryError = null,
                pageError = null,
                isLoadingCategories = true,
                isLoadingPage = false,
                isLoadingMore = false,
                loadedCount = 0,
                totalCount = 0,
                hasMore = false,
                online = false,
                serverTitle = "",
                serverVersion = "",
            )
        }
    }

    // 底部状态栏与顶栏状态用的服务端信息。失败不弹错：离线时它只是把状态点变灰。
    private suspend fun refreshServerStatus(activeRepository: LumigatherRepository) {
        when (val result = activeRepository.loadStatus()) {
            is ApiResult.Ok -> {
                // needsSetup 由服务端判定（它才知道有没有 passwordHash）；归登录子状态机管，
                // 用它刷新标记是为了让"这台服务器没设密码"的提醒在重启后依然成立。
                onServerNeedsSetup(result.value.needsSetup)
                channel.mutate {
                    it.copy(online = result.value.ok, serverVersion = result.value.version)
                }
            }

            is ApiResult.Err -> channel.mutate { it.copy(online = false) }
        }
    }

    /**
     * 加载当前这一层（走 `/api/dir`，不是 `/api/images`）。
     *
     * 为什么换接口：/api/images 只回图片，拿不到"这一层有哪些子目录、各有多少张、是不是空"，
     * 而层级浏览全靠这三件事。/api/dir 一次给全，且不分页（服务端按层返回本层全部图片）。
     */
    private suspend fun loadCurrentDir(categoryName: String) {
        val activeRepository = repositoryProvider() ?: return
        val rootPath = channel.state.categories.firstOrNull { it.name == categoryName }?.rootPath.orEmpty()
        val relativePath = currentRelativePath()

        channel.mutate {
            it.copy(
                isLoadingPage = true,
                isLoadingMore = false,
                pageError = null,
                // 换层：上一层的条目与包内图都作废（包内图由归档子状态机随新目录重扫）
                dirEntries = emptyList(),
                archiveImages = emptyList(),
                hasMore = false,
            )
        }
        publishBreadcrumbs(categoryName)

        when (val result = activeRepository.loadDir(rootPath, relativePath)) {
            is ApiResult.Ok -> {
                val entries = mapDirToEntries(result.value)
                // 换层后若原来放大的那张已不在本层（或换了目录），必须清空：
                // 否则会留下一张"看不见的图"仍在放大集合里，界面表现为下次进目录莫名放大
                val expandedKey = channel.state.expandedEntryKey
                val stillPresent = expandedKey != null && entries.any { entry ->
                    entry is GridEntry.Picture && entry.item.absoluteUrl == expandedKey
                }
                channel.mutate {
                    it.copy(
                        dirEntries = entries,
                        expandedEntryKey = if (stillPresent) expandedKey else null,
                        isLoadingPage = false,
                        // dir 接口不分页：本层一次给全，所以不存在"还有更多"
                        hasMore = false,
                        loadedCount = result.value.images.size,
                        totalCount = result.value.images.size,
                    )
                }
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(isLoadingPage = false, pageError = result.error)
            }
        }
    }

    // 面包屑 = 分类名 + 栈里每一层；能否上级 = 栈非空
    private fun publishBreadcrumbs(categoryName: String) {
        val crumbs = listOf(categoryName) + pathStack
        channel.mutate { it.copy(breadcrumbs = crumbs, canGoUp = pathStack.isNotEmpty()) }
    }

    private fun applyPage(page: PagedImages, append: Boolean) {
        channel.mutate { current ->
            val merged = if (append) current.images + page.images else page.images
            current.copy(
                images = merged,
                isLoadingPage = false,
                isLoadingMore = false,
                pageError = null,
                loadedCount = merged.size,
                totalCount = page.total,
                hasMore = !page.isEnd,
            )
        }
    }

    /**
     * 后台补各分类张数。并发上限 3：单个请求要扫盘（实测最慢 3.4 秒），
     * 8 个全并发会把 NAS 的盘压住，3 个是"明显变快又不打爆"的折中。
     *
     * 回调时机：数据层在整批探测合并之后统一回调，所以界面表现为"张数整批到齐后刷新一次"。
     */
    private suspend fun fillCategoryCounts(categoryNames: List<String>) {
        val activeRepository = repositoryProvider() ?: return
        activeRepository.refreshCategoryCounts(
            categoryNames = categoryNames,
            maxConcurrent = DEFAULT_COUNT_CONCURRENCY,
        ) { updated ->
            channel.mutate { current ->
                current.copy(categories = current.categories.map { existing ->
                    if (existing.name == updated.name) updated else existing
                })
            }
        }
    }
}

/**
 * `/api/dir` 的一层 → 网格条目（三条渲染规则的唯一落点）。
 *
 * 规则（实测数据确认过）：
 * 1. `hasSub = true` → 文件夹卡片。**不看 count**：count = -1 表示"未知"（后端递归缓存没覆盖时
 *    单层扫描回落会这样给，实测 65 个目录全是 -1），把它当 0 会把有图目录直接吞掉
 * 2. `hasSub = false` 且有 leafImages → 不画卡片，图片直接摊进网格（叶子目录不该再点一次）
 * 3. `hasSub = false` 且没有图 → 不产出条目（真空目录不占位）
 *
 * images（本层直接含的图）排在子目录之后：先看到"还有哪些目录"，再看本层散图。
 */
internal fun mapDirToEntries(page: DirPage): List<GridEntry> {
    val entries = ArrayList<GridEntry>(page.subdirs.size + page.images.size)
    page.subdirs.forEach { subdir ->
        when {
            subdir.hasSub -> entries.add(
                GridEntry.Folder(name = subdir.name, path = subdir.relPath, count = subdir.count),
            )

            subdir.leafImages.isNotEmpty() -> subdir.leafImages.forEach { image ->
                entries.add(GridEntry.Picture(image))
            }
            // else：无子目录且无图 → 真空目录，不产出
        }
    }
    page.images.forEach { image -> entries.add(GridEntry.Picture(image)) }
    return entries
}
