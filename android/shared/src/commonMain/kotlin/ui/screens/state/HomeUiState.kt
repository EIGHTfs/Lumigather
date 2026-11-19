// 页面状态与它的唯一修改者。
//
// 分工：这个文件只管"状态长什么样"和"数据怎么装进状态"，一行布局都不写；
// main/ 与 sidebar/ 与 settings/ 下的文件只管"状态怎么画"，一行业务判断都不写。
//
// 地址来源的变化（T8）：从前是宿主通过 pageData 传进来，现在改成"启动时从持久化读取"。
// 读取顺序是硬约定：**已保存的地址 > 没有就进设置态**，不存在任何硬编码兜底地址 ——
// 有兜底的话用户永远发现不了自己没配过，出了问题也只会怀疑"服务端坏了"。

package ui.screens.state

import androidx.compose.runtime.mutableStateOf
import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.CategoryView
import com.eightfs.lumigather.data.DEFAULT_COUNT_CONCURRENCY
import com.eightfs.lumigather.data.DEFAULT_PAGE_SIZE
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.PagedImages
import ui.screens.settings.ServerAddressRules
import ui.screens.settings.ServerAddressStore

/** 测试连接的四种结局。Testing 与 Idle 分开，是为了让按钮能在进行中禁用并可读地显示"连接中"。 */
sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data object Testing : ConnectionTestState()
    data class Success(val version: String) : ConnectionTestState()
    data class Failure(val message: String) : ConnectionTestState()
}

/**
 * 整屏的完整状态。任何时刻界面都是它的一个纯函数。
 *
 * 三个错误字段刻意分开（[categoryError] / [pageError] / 分类项里的 countState）：
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
    // ---- 服务器地址设置 ----
    // 是否停在设置界面。未配置地址时它为 true，这是冷启动的第一屏。
    val settingsOpen: Boolean = false,
    // 设置界面的输入草稿。与"已保存地址"分开：用户可能改了又取消。
    val addressDraft: String = "",
    // 输入校验/保存提示（中文，可直接上屏）
    val addressError: String? = null,
    // 测试连接的结论
    val testState: ConnectionTestState = ConnectionTestState.Idle,
    // 是否已有可用配置。决定设置界面要不要给"返回"入口 —— 没配过就没有可返回的地方。
    val hasSavedAddress: Boolean = false,
) {
    // 左侧栏的可见分类：搜索框有内容时按展示名过滤，空查询不过滤。
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
 *
 * 为什么收 clientFactory 而不是现成的 repository：改地址后必须换掉整个 ApiClient（baseUrl 变了），
 * 由这里统一重建，页面不必关心"什么时候该换"。
 */
class HomeStateHolder(
    private val clientFactory: (String) -> ApiClient,
    private val addressStore: ServerAddressStore,
) {

    private val mutableState = mutableStateOf(HomeUiState())

    val state: HomeUiState
        get() = mutableState.value

    // 当前生效的仓库；地址未配置时为 null（此时界面在设置态）。
    private var repository: LumigatherRepository? = null

    // 当前 repository 对应的地址：用来判断"地址是否真的变了"，没变就不重复重建。
    private var activeAddress: String = ""

    // 唯一的写入口。集中一处写的好处：将来要加日志/埋点只改这里。
    private fun mutate(transform: (HomeUiState) -> HomeUiState) {
        mutableState.value = transform(mutableState.value)
    }

    /**
     * 启动链路：读已保存地址 →（没有就进设置态）→ 状态 → 分类 → 第一个分类的第一页 → 后台补张数。
     */
    suspend fun bootstrap() {
        val savedAddress = addressStore.read()
        if (savedAddress.isBlank()) {
            enterSettingsMode(hasSavedAddress = false)
            return
        }
        attachRepository(savedAddress)
        refreshContent()
    }

    // 运行中打开设置：预填当前地址，方便用户只改端口这类小改动。
    fun openSettings() {
        val savedAddress = addressStore.read()
        mutate {
            it.copy(
                settingsOpen = true,
                addressDraft = savedAddress,
                addressError = null,
                testState = ConnectionTestState.Idle,
                hasSavedAddress = savedAddress.isNotBlank(),
                leftSidebarOpen = false,
                rightSidebarOpen = false,
            )
        }
    }

    fun updateAddressDraft(address: String) {
        mutate { it.copy(addressDraft = address, addressError = null) }
    }

    /**
     * 只测试不保存：用户可能只是想确认"地址对不对"，没打算立刻切换。
     *
     * 用一个临时的 ApiClient（不塞进 repository）：测试失败不该影响正在显示的旧数据。
     */
    suspend fun testAddress() {
        val normalized = ServerAddressRules.normalize(state.addressDraft)
        if (normalized == null) {
            mutate {
                it.copy(
                    addressError = ServerAddressRules.errorMessageFor(state.addressDraft),
                    testState = ConnectionTestState.Idle,
                )
            }
            return
        }

        mutate { it.copy(addressError = null, testState = ConnectionTestState.Testing) }
        val result = clientFactory(normalized).fetchStatus()
        mutate { current ->
            current.copy(
                testState = when (result) {
                    is ApiResult.Ok -> ConnectionTestState.Success(result.value.version)
                    is ApiResult.Err -> ConnectionTestState.Failure(result.error.userMessage)
                }
            )
        }
    }

    /**
     * 保存并立即生效：校验 → 持久化 → 重建数据层 → 重跑首屏。
     *
     * "立即生效"是这个功能的重点：改完地址要求用户重启应用，等于把配置藏起来。
     * 这里保存后直接 refreshContent()，不重建 Activity、不重启进程。
     */
    suspend fun saveAddress() {
        val normalized = ServerAddressRules.normalize(state.addressDraft)
        if (normalized == null) {
            mutate { it.copy(addressError = ServerAddressRules.errorMessageFor(state.addressDraft)) }
            return
        }

        addressStore.write(normalized)
        // 清掉旧地址的数据再重载：留着旧分类会让用户以为"新地址也是这些图"
        mutate {
            it.copy(
                settingsOpen = false,
                hasSavedAddress = true,
                addressDraft = normalized,
                addressError = null,
                testState = ConnectionTestState.Idle,
                categories = emptyList(),
                images = emptyList(),
                selectedImage = null,
                selectedCategory = "",
                searchQuery = "",
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
        // 地址变了就必须换 ApiClient；attachRepository 用 activeAddress 判断是否需要重建
        attachRepository(normalized, forceRebuild = true)
        refreshContent()
    }

    // 切换分类：先收起左侧栏（用户点完就想看结果），再清空网格重新拉第一页。
    suspend fun selectCategory(categoryName: String) {
        if (categoryName == state.selectedCategory && state.images.isNotEmpty()) {
            mutate { it.copy(leftSidebarOpen = false) }
            return
        }
        mutate {
            it.copy(
                selectedCategory = categoryName,
                leftSidebarOpen = false,
                rightSidebarOpen = false,
                selectedImage = null,
            )
        }
        loadFirstPage(categoryName)
    }

    /**
     * 触底加载下一页。到底判据直接用数据层的 [PagedImages.isEnd]，
     * 页面与状态都不自己拿 total 算 —— 后端在越界请求时会回 returned=0 但 total 仍是真实值，
     * 自己算很容易多请求一次空页。
     */
    suspend fun loadMore() {
        val current = state
        val activeRepository = repository ?: return
        if (current.isLoadingMore || current.isLoadingPage || !current.hasMore) return
        if (current.selectedCategory.isEmpty()) return

        mutate { it.copy(isLoadingMore = true) }
        when (val result = activeRepository.loadPageForCategory(
            categoryName = current.selectedCategory,
            offset = current.loadedCount,
            limit = DEFAULT_PAGE_SIZE,
        )) {
            is ApiResult.Ok -> applyPage(result.value, append = true)
            is ApiResult.Err -> mutate { it.copy(isLoadingMore = false, pageError = result.error) }
        }
    }

    // 重试：没配置就回设置态；分类都没拿到就整条链路重来；只是图片页失败就只重拉当前页。
    suspend fun retry() {
        if (repository == null) {
            bootstrap()
            return
        }
        val categoryName = state.selectedCategory
        if (state.categories.isEmpty() || categoryName.isEmpty()) {
            refreshContent()
            return
        }
        loadFirstPage(categoryName)
    }

    fun openLeftSidebar() {
        mutate { it.copy(leftSidebarOpen = true, rightSidebarOpen = false) }
    }

    // 手势开右栏：不指定图片。右栏自己会处理 selectedImage == null 的空态，
    // 所以从右侧边缘滑出时看到的是「提示去点图片」而不是空白面板。
    fun openRightSidebar() {
        mutate { it.copy(rightSidebarOpen = true, leftSidebarOpen = false) }
    }

    fun openDetail(image: ImageItem) {
        mutate { it.copy(rightSidebarOpen = true, selectedImage = image, leftSidebarOpen = false) }
    }

    fun closeSidebars() {
        mutate { it.copy(leftSidebarOpen = false, rightSidebarOpen = false) }
    }

    // 从设置界面退回主界面。没配过地址时不允许退：退出去也没有内容可看。
    fun closeSettings() {
        if (!state.hasSavedAddress) return
        mutate { it.copy(settingsOpen = false, addressError = null, testState = ConnectionTestState.Idle) }
    }

    fun updateSearchQuery(query: String) {
        mutate { it.copy(searchQuery = query) }
    }

    private fun enterSettingsMode(hasSavedAddress: Boolean) {
        mutate {
            it.copy(
                isLoadingCategories = false,
                settingsOpen = true,
                addressDraft = "",
                addressError = null,
                testState = ConnectionTestState.Idle,
                hasSavedAddress = hasSavedAddress,
            )
        }
    }

    // 地址变了才重建仓库：重复重建会白丢连接池与已缓存的分类。
    private fun attachRepository(address: String, forceRebuild: Boolean = false) {
        if (!forceRebuild && repository != null && activeAddress == address) return
        repository = LumigatherRepository(clientFactory(address))
        activeAddress = address
    }

    // 地址落定之后的主链路：状态 → 分类 → 第一个分类的第一页 → 后台补张数。
    private suspend fun refreshContent() {
        val activeRepository = repository ?: return
        mutate { it.copy(isLoadingCategories = true, categoryError = null) }
        refreshServerStatus()

        when (val result = activeRepository.loadCategories()) {
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
                        serverTitle = activeRepository.title,
                        selectedCategory = firstCategory?.name.orEmpty(),
                        hasSavedAddress = true,
                    )
                }
                if (firstCategory == null) return
                loadFirstPage(firstCategory.name)
                fillCategoryCounts(categories.map { it.name })
            }
        }
    }

    // 底部状态栏与顶栏状态用的服务端信息。失败不弹错：离线时它只是把状态点变灰。
    private suspend fun refreshServerStatus() {
        val activeRepository = repository ?: return
        when (val result = activeRepository.loadStatus()) {
            is ApiResult.Ok -> mutate {
                it.copy(online = result.value.ok, serverVersion = result.value.version)
            }

            is ApiResult.Err -> mutate { it.copy(online = false) }
        }
    }

    private suspend fun loadFirstPage(categoryName: String) {
        val activeRepository = repository ?: return
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
        when (val result = activeRepository.loadPageForCategory(
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
     * 回调时机：数据层在整批探测合并之后统一回调，所以界面表现为"张数整批到齐后刷新一次"。
     * 要改成逐条刷新得改数据层，这里不私自在页面层并发单发（会重现仓库缓存的丢更新）。
     */
    private suspend fun fillCategoryCounts(categoryNames: List<String>) {
        val activeRepository = repository ?: return
        activeRepository.refreshCategoryCounts(
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
