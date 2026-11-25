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
                loadFirstPage(firstCategory.name)
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
        loadFirstPage(categoryName)
    }

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
        loadFirstPage(categoryName)
    }

    /**
     * 清空"属于旧服务器"的浏览数据（换地址时由 facade 调用）。
     *
     * 不清的话，用户会以为新地址下也是这些图；而 isLoadingCategories=true 是为了让界面
     * 立刻回到"正在读取分类"的加载态，而不是闪一下旧数据的空壳。
     */
    fun resetContent() {
        channel.mutate {
            it.copy(
                categories = emptyList(),
                images = emptyList(),
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

    private suspend fun loadFirstPage(categoryName: String) {
        val activeRepository = repositoryProvider() ?: return
        channel.mutate {
            it.copy(
                isLoadingPage = true,
                isLoadingMore = false,
                pageError = null,
                images = emptyList(),
                loadedCount = 0,
                totalCount = 0,
                hasMore = false,
            )
        }
        when (val result = activeRepository.loadPageForCategory(
            categoryName = categoryName,
            offset = 0,
            limit = DEFAULT_PAGE_SIZE,
        )) {
            is ApiResult.Ok -> applyPage(result.value, append = false)
            is ApiResult.Err -> channel.mutate { it.copy(isLoadingPage = false, pageError = result.error) }
        }
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
