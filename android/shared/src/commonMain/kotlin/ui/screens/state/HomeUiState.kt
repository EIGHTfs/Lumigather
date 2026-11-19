// 页面状态与它的唯一修改者。
//
// 分工：这个文件只管"状态长什么样"和"数据怎么装进状态"，一行布局都不写；
// main/ 与 sidebar/ 下的文件只管"状态怎么画"，一行业务判断都不写。
// 这样调布局不会碰到数据流，调数据流也不会碰坏布局。
//
// 状态用 Compose 的 Snapshot 状态承载：页面读 state 的字段就自动订阅变更，
// 不需要页面自己去转发事件或做 diff。

package ui.screens.state

import androidx.compose.runtime.mutableStateOf
import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.CategoryView
import com.eightfs.lumigather.data.DEFAULT_COUNT_CONCURRENCY
import com.eightfs.lumigather.data.DEFAULT_PAGE_SIZE
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.PagedImages

/**
 * 整屏的完整状态。任何时刻界面都是它的一个纯函数。
 *
 * 三个错误字段刻意分开（[categoryError] / [pageError] / [countState] 在分类项里）：
 * 分类列表拉不到、某一页图片拉不到、某个分类的张数统计失败，是三件不同的坏事，
 * 用户该看到的提示与重试入口也不同，混成一个 error 就没法区别对待。
 */
data class HomeUiState(
    val serverTitle: String = "",
    val serverVersion: String = "",
    val online: Boolean = false,
    val categories: List<CategoryView> = emptyList(),
    val selectedCategory: String = "",
    val images: List<ImageItem> = emptyList(),
    val isLoadingCategories: Boolean = true,
    // 首页第一页：只有它的转圈要占满内容区，翻页的转圈只出现在网格尾部
    val isLoadingPage: Boolean = false,
    val isLoadingMore: Boolean = false,
    val categoryError: ApiError? = null,
    val pageError: ApiError? = null,
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val leftSidebarOpen: Boolean = false,
    val rightSidebarOpen: Boolean = false,
    val selectedImage: ImageItem? = null,
    val searchQuery: String = "",
) {
    // 左侧栏的可见分类：搜索框有内容时按名称/展示名过滤，空查询不过滤。
    // 过滤放在状态里而不是页面里，是为了让"搜索后选中项还在不在"这类一致性问题只有一处判断。
    val visibleCategories: List<CategoryView>
        get() {
            val keyword = searchQuery.trim()
            if (keyword.isEmpty()) return categories
            return categories.filter { it.label.contains(keyword, ignoreCase = true) }
        }

    val selectedCategoryView: CategoryView?
        get() = categories.firstOrNull { it.name == selectedCategory }

    // 底部状态栏文案。连上了还要区分"服务端自报 ok=false"（进程活着但状态异常）。
    val statusText: String
        get() = if (online) "在线" else "离线"

    // 顶栏副标题：分类名 + 已加载/总数，让用户知道"还有多少没加载"。
    val contentSubtitle: String
        get() {
            val categoryName = selectedCategoryView?.label.orEmpty()
            if (categoryName.isEmpty()) return "正在读取分类"
            if (totalCount <= 0) return categoryName
            return "$categoryName · ${loadedCount}/${totalCount}"
        }
}

/**
 * 状态持有者：所有网络调用与状态迁移都收在这里。
 *
 * 对外全是 suspend 方法：调用方（页面）在自己的协程作用域里调，页面销毁时作用域取消，
 * 请求跟着取消，不会出现"页面没了但回调还在改状态"。
 */
class HomeStateHolder(private val repository: LumigatherRepository) {

    private val mutableState = mutableStateOf(HomeUiState())

    val state: HomeUiState
        get() = mutableState.value

    // 唯一的写入口。集中一处写的好处：将来要加日志/埋点只改这里。
    private fun mutate(transform: (HomeUiState) -> HomeUiState) {
        mutableState.value = transform(mutableState.value)
    }

    /**
     * 启动链路：状态 → 分类 → 第一个分类的第一页 → 后台补各分类张数。
     *
     * 张数统计放在最后且不 await 它影响首屏：8 个分类各要扫一次盘（实测冷启动合计 25 秒），
     * 等它等于白屏 25 秒。这里先让网格出来，张数到位一条刷一条。
     */
    suspend fun bootstrap() {
        mutate { it.copy(isLoadingCategories = true, categoryError = null) }
        refreshServerStatus()

        when (val result = repository.loadCategories()) {
            is ApiResult.Err -> mutate {
                it.copy(isLoadingCategories = false, categoryError = result.error)
            }

            is ApiResult.Ok -> {
                val categories = result.value
                val firstCategory = categories.firstOrNull()
                mutate {
                    it.copy(
                        isLoadingCategories = false,
                        categories = categories,
                        serverTitle = repository.title,
                        selectedCategory = firstCategory?.name.orEmpty(),
                    )
                }
                if (firstCategory == null) return
                loadFirstPage(firstCategory.name)
                fillCategoryCounts(categories.map { it.name })
            }
        }
    }

    // 切换分类：先收起左侧栏（用户点完就想看结果），再清空网格重新拉第一页。
    // 不保留上一个分类的图，是因为「切换后网格原地变化」要求视觉上立刻换掉，
    // 留着旧图反而会让人以为没生效。
    suspend fun selectCategory(categoryName: String) {
        if (categoryName == state.selectedCategory && state.images.isNotEmpty()) {
            mutate { it.copy(leftSidebarOpen = false) }
            return
        }
        mutate { it.copy(selectedCategory = categoryName, leftSidebarOpen = false, rightSidebarOpen = false, selectedImage = null) }
        loadFirstPage(categoryName)
    }

    /**
     * 触底加载下一页。到底判据直接用数据层的 [PagedImages.isEnd]，
     * 页面与状态都不自己拿 total 算 —— 后端在越界请求时会回 returned=0 但 total 仍是真实值，
     * 自己算很容易多请求一次空页。
     */
    suspend fun loadMore() {
        val current = state
        if (current.isLoadingMore || current.isLoadingPage || !current.hasMore) return
        if (current.selectedCategory.isEmpty()) return

        mutate { it.copy(isLoadingMore = true) }
        when (val result = repository.loadPageForCategory(
            categoryName = current.selectedCategory,
            offset = current.loadedCount,
            limit = DEFAULT_PAGE_SIZE,
        )) {
            is ApiResult.Ok -> applyPage(result.value, append = true)
            is ApiResult.Err -> mutate { it.copy(isLoadingMore = false, pageError = result.error) }
        }
    }

    // 重试：分类都没拿到就整条链路重来；只是图片页失败就只重拉当前页。
    suspend fun retry() {
        if (state.categories.isEmpty()) {
            bootstrap()
            return
        }
        val categoryName = state.selectedCategory
        if (categoryName.isEmpty()) {
            bootstrap()
            return
        }
        loadFirstPage(categoryName)
    }

    fun openLeftSidebar() {
        mutate { it.copy(leftSidebarOpen = true, rightSidebarOpen = false) }
    }

    fun openDetail(image: ImageItem) {
        mutate { it.copy(rightSidebarOpen = true, selectedImage = image, leftSidebarOpen = false) }
    }

    fun closeSidebars() {
        mutate { it.copy(leftSidebarOpen = false, rightSidebarOpen = false) }
    }

    fun updateSearchQuery(query: String) {
        mutate { it.copy(searchQuery = query) }
    }

    // 底部状态栏与顶栏状态用的服务端信息。失败不弹错：离线时它只是把状态点变灰。
    private suspend fun refreshServerStatus() {
        when (val result = repository.loadStatus()) {
            is ApiResult.Ok -> mutate {
                it.copy(online = result.value.ok, serverVersion = result.value.version)
            }

            is ApiResult.Err -> mutate { it.copy(online = false) }
        }
    }

    private suspend fun loadFirstPage(categoryName: String) {
        mutate {
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
        when (val result = repository.loadPageForCategory(
            categoryName = categoryName,
            offset = 0,
            limit = DEFAULT_PAGE_SIZE,
        )) {
            is ApiResult.Ok -> applyPage(result.value, append = false)
            is ApiResult.Err -> mutate { it.copy(isLoadingPage = false, pageError = result.error) }
        }
    }

    private fun applyPage(page: PagedImages, append: Boolean) {
        mutate { current ->
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
     * 回调时机说明：数据层是在整批探测合并之后统一回调的，所以界面上表现为
     * "张数整批到齐后一次刷新"，不是逐个到齐刷新。要改成逐条刷新得改数据层，
     * 这里按现有契约实现，不私自在页面层做并发单发（那会重现仓库缓存的丢更新）。
     */
    private suspend fun fillCategoryCounts(categoryNames: List<String>) {
        repository.refreshCategoryCounts(
            categoryNames = categoryNames,
            maxConcurrent = DEFAULT_COUNT_CONCURRENCY,
        ) { updated ->
            mutate { current ->
                current.copy(categories = current.categories.map { existing ->
                    if (existing.name == updated.name) updated else existing
                })
            }
        }
    }
}
