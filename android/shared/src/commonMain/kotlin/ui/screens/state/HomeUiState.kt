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
import com.eightfs.lumigather.data.DEFAULT_SESSION_HOURS
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.PagedImages
import com.eightfs.lumigather.data.StoredAuthToken
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.currentEpochMillis
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
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
 * 下载到相册的四种状态。
 *
 * 为什么成功也要带 message：三种"成功"对用户的意义不同 —— 新存了一份、相册里本来就有、
 * 刚被系统拦下来一次又重试成功。只显示一个对勾会让用户搞不清相册里到底有几份。
 */
sealed class DownloadUiState {
    data object Idle : DownloadUiState()
    data object Downloading : DownloadUiState()
    data class Done(val message: String) : DownloadUiState()
    data class Failed(val message: String) : DownloadUiState()
}

/**
 * 登录弹层的状态。
 *
 * [Failed] 带的是**后端原文**（例如「密码错误」）：用户要看到的是"为什么不行"，
 * 而不是我们翻译过一遍的笼统话术。拿不到后端文案时由状态层兜一句可操作的提示。
 */
sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Submitting : LoginUiState()
    data class Failed(val message: String) : LoginUiState()
}

/**
 * 本地登录态。
 *
 * [expiresAtEpochMs] 是客户端按服务端 TTL 推算的到期时刻；为 0 表示未登录。
 * [noPassword] 记录"这台服务器还没设密码"，用来提示用户尽快设置（服务端会一直放行）。
 */
data class AuthUiState(
    val loggedIn: Boolean = false,
    val rememberDevice: Boolean = true,
    val expiresAtEpochMs: Long = 0L,
    val noPassword: Boolean = false,
)

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
    // ---- 详情栏动作 ----
    // 下载到相册的进度/结果
    val downloadState: DownloadUiState = DownloadUiState.Idle,
    // 占位功能（收藏）的提示。与 downloadState 分开：收藏按设计就是不落地的，
    // 把它混进下载状态会让"下载失败"和"需要登录"共用一条通道，将来接入登录时更难拆。
    val actionHint: String? = null,
    // ---- 登录（登录底座）----
    val auth: AuthUiState = AuthUiState(),
    val loginSheetOpen: Boolean = false,
    val loginPassword: String = "",
    // 「记住此设备」默认勾选：与网页版登录弹窗一致，也是绝大多数用户想要的
    val loginRemember: Boolean = true,
    val loginState: LoginUiState = LoginUiState.Idle,
    // 失败后的冷却期（后端没有失败限流，前端用这个挡住连点）
    val loginBlocked: Boolean = false,
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

    // 登录按钮可不可点：冷却中、提交中都不行；密码为空也不行 —— 除非这台服务器压根没设密码
    // （那种情况下服务端会直接放行，逼用户乱填一个才奇怪）。三个条件缺一不可，
    // 少了"冷却"就挡不住连点穷举，少了"密码为空"会让用户点了没反应。
    val loginSubmittable: Boolean
        get() = !loginBlocked &&
            loginState !is LoginUiState.Submitting &&
            (loginPassword.isNotEmpty() || auth.noPassword)

    // 设置页「账号」块的一行摘要。有效期按勾选状态给"30 天/3 天"，不显示精确时间：
    // 那个时刻是客户端推算的，显示成精确时间反而会让人以为很准。
    val authSummary: String
        get() = when {
            !auth.loggedIn -> "未登录"
            auth.rememberDevice -> "已登录 · 30 天内有效"
            else -> "已登录 · 3 天内有效"
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
    // 造 ApiClient 的工厂。第二个参数是 401 回调：凭证失效是全局事实，
    // 由 ApiClient 在统一位置回调一次，这里负责清 token + 提示重新登录。
    private val clientFactory: (String, () -> Unit) -> ApiClient,
    private val addressStore: ServerAddressStore,
    private val imageDownloader: ImageDownloader,
    private val authTokenStore: AuthTokenStore,
    // 时钟可注入：测试要稳定构造"已过期 token"这种场景，不能依赖真实时间流逝
    private val nowProvider: () -> Long = { currentEpochMillis() },
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
        // 先恢复登录态：它只依赖本地存储与当前时间，与服务器是否可达无关
        restoreAuthSession()
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
        // 临时 client 也接 401 回调：连的可能是另一台服务器，凭证失效同样要清本地 token
        val result = clientFactory(normalized, ::handleUnauthorized).fetchStatus()
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
        // 换服务器必须清 token：旧 token 是上一台服务器签的，对新服务器一律无效，
        // 留着它的唯一后果是之后每次带凭证的请求都 401，而用户完全看不出原因。
        authTokenStore.clear()
        // 清掉旧地址的数据再重载：留着旧分类会让用户以为"新地址也是这些图"
        mutate {
            it.copy(
                settingsOpen = false,
                hasSavedAddress = true,
                addressDraft = normalized,
                addressError = null,
                testState = ConnectionTestState.Idle,
                auth = AuthUiState(),
                loginSheetOpen = false,
                loginPassword = "",
                loginState = LoginUiState.Idle,
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
        mutate {
            it.copy(
                rightSidebarOpen = true,
                selectedImage = image,
                leftSidebarOpen = false,
                // 换图就清掉上一张的下载结果与提示，否则用户会以为新图"已保存到相册"
                downloadState = DownloadUiState.Idle,
                actionHint = null,
            )
        }
    }

    /**
     * 下载当前详情图到系统相册。
     *
     * 防重复：一开始就把状态切成 Downloading，第二次调用在入口处直接返回。
     * 因为状态是同步写入的（在第一个挂起点之前），连点两下也只有一次会真正跑下去。
     *
     * 失败不弹窗、不崩：分类里的三种失败原因各有中文提示，直接显示在动作条上方。
     */
    suspend fun downloadSelectedImage() {
        val image = state.selectedImage ?: return
        if (state.downloadState is DownloadUiState.Downloading) return

        mutate { it.copy(downloadState = DownloadUiState.Downloading, actionHint = null) }

        // 结果一次性算成一个新状态再写回：中间不暴露"Downloading 已被清掉但结果还没写"的空档，
        // 否则按钮会在半路恢复可点。
        //
        // 外层兜一层 catch 是防御性的：接口约定"实现不抛异常"，但平台实现可能有漏网的异常类型，
        // 一旦抛出来没有兜底，状态就会永久停在"保存中…"、按钮永久禁用 —— 那是最难受的一种坏。
        val nextState = try {
            when (val result = imageDownloader.saveToGallery(image.absoluteUrl, image.name)) {
                is DownloadResult.Success -> DownloadUiState.Done("已保存到相册")
                is DownloadResult.AlreadySaved -> DownloadUiState.Done("相册里已有这张图，未重复保存")
                is DownloadResult.Failure -> DownloadUiState.Failed(result.message)
            }
        } catch (cancelled: CancellationException) {
            // 协程被取消（页面销毁等）：先把状态复位，再把取消原样抛出 ——
            // 吞掉取消会让上层的结构化并发逻辑失效。
            mutate { it.copy(downloadState = DownloadUiState.Idle) }
            throw cancelled
        } catch (failure: Throwable) {
            DownloadUiState.Failed("保存失败：${failure.message ?: "未知错误"}")
        }
        mutate { it.copy(downloadState = nextState) }
    }

    /**
     * 收藏：本轮只做占位提示，**不做任何状态切换**。
     *
     * 为什么宁可"点了没反应式地提示"也不做假的已收藏态：假状态会让用户以为存到了服务端，
     * 等真正接登录时才发现以前的收藏一个都没有 —— 那比现在直接告诉他"还没开放"糟糕得多。
     */
    fun requestFavorite() {
        // 本轮只改提示，不做任何"已收藏"状态切换：假状态会让用户以为存到了服务端。
        // 未登录时先引导登录 —— 登录底座已经可用，收藏接口下一轮再接。
        if (!state.auth.loggedIn) {
            openLoginSheet()
            return
        }
        mutate { it.copy(actionHint = "收藏功能即将开放") }
    }

    // ---- 登录底座 ----

    /** 打开登录弹层：清空上次的密码与错误，避免把敏感内容留在界面上。 */
    fun openLoginSheet() {
        mutate {
            it.copy(
                loginSheetOpen = true,
                loginPassword = "",
                loginState = LoginUiState.Idle,
                loginBlocked = false,
                loginRemember = true,
                actionHint = null,
            )
        }
    }

    fun closeLoginSheet() {
        mutate { it.copy(loginSheetOpen = false, loginPassword = "", loginState = LoginUiState.Idle, loginBlocked = false) }
    }

    fun updateLoginPassword(password: String) {
        // 改密码时顺手清掉错误提示：用户已经在改了，还挂着「密码错误」会让人以为没生效
        mutate { it.copy(loginPassword = password, loginState = LoginUiState.Idle) }
    }

    fun toggleLoginRemember(remember: Boolean) {
        mutate { it.copy(loginRemember = remember) }
    }

    /**
     * 提交登录。
     *
     * 失败的处置有两件事：内联显示后端原文（如「密码错误」）+ 冷却 1.5 秒。
     * 冷却放在这里而不是 UI：按钮只是表现，真正的闸门在状态层 —— 否则任何直接调用都能绕过。
     */
    suspend fun submitLogin() {
        if (!state.loginSubmittable) return
        val activeRepository = repository
            ?: run {
                mutate { it.copy(loginState = LoginUiState.Failed("请先配置服务器地址")) }
                return
            }

        val password = state.loginPassword
        val remember = state.loginRemember
        mutate { it.copy(loginState = LoginUiState.Submitting) }

        when (val result = activeRepository.login(password, remember)) {
            is ApiResult.Ok -> {
                val session = result.value
                val expiresAt = session.expiresAt(nowProvider())
                authTokenStore.write(session.token, expiresAt)
                mutate {
                    it.copy(
                        loginSheetOpen = false,
                        // 关掉弹层就把密码从内存里清掉，别让它留在状态里
                        loginPassword = "",
                        loginState = LoginUiState.Idle,
                        loginBlocked = false,
                        auth = AuthUiState(
                            loggedIn = true,
                            rememberDevice = remember,
                            expiresAtEpochMs = expiresAt,
                            noPassword = session.noPassword,
                        ),
                        actionHint = if (session.noPassword) {
                            "服务端未设置密码，建议尽快设置"
                        } else {
                            "登录成功"
                        },
                    )
                }
            }

            is ApiResult.Err -> {
                mutate { it.copy(loginState = LoginUiState.Failed(describeLoginFailure(result.error)), loginBlocked = true) }
                // 冷却：后端没有失败限流/锁定，前端必须自己挡住连点穷举
                delay(FAILED_LOGIN_COOLDOWN_MS)
                mutate { it.copy(loginBlocked = false) }
            }
        }
    }

    /** 退出登录：先尽力通知服务端，再无条件清本地 —— 本地退不掉会把用户困在登录态。 */
    suspend fun logout() {
        val token = authTokenStore.read()?.token.orEmpty()
        repository?.logout(token)
        clearAuthSession()
        mutate { it.copy(actionHint = "已退出登录") }
    }

    // 读本地会话：过期/格式异常一律当未登录，并把坏数据清掉（避免每次启动都带着坏值）
    private fun restoreAuthSession() {
        val stored = authTokenStore.read()
        if (stored == null) {
            mutate { it.copy(auth = AuthUiState()) }
            return
        }
        if (stored.isExpired(nowProvider())) {
            authTokenStore.clear()
            mutate { it.copy(auth = AuthUiState()) }
            return
        }
        // 记住勾选状态无法从存储反推，用"剩余时间接近 30 天"判断当时勾没勾；
        // 判错只影响摘要文案（30 天/3 天），不影响会话有效性。
        val remainingHours = (stored.expiresAtEpochMs - nowProvider()) / MILLIS_PER_HOUR
        mutate {
            it.copy(
                auth = AuthUiState(
                    loggedIn = true,
                    rememberDevice = remainingHours > DEFAULT_SESSION_HOURS,
                    expiresAtEpochMs = stored.expiresAtEpochMs,
                ),
            )
        }
    }

    /**
     * 401 回调（ApiClient 在非登录请求收到 401 时调一次）。
     *
     * 必须"清 token + 明确提示"，不能静默：用户会以为是网络坏了，
     * 然后反复点同一个按钮；其实只要重新登录就能恢复。
     */
    private fun handleUnauthorized() {
        clearAuthSession()
        mutate { it.copy(actionHint = "登录已过期，请重新登录") }
    }

    private fun clearAuthSession() {
        authTokenStore.clear()
        mutate { it.copy(auth = AuthUiState()) }
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
        repository = LumigatherRepository(clientFactory(address, ::handleUnauthorized))
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
                // needsSetup 由服务端判定（它才知道有没有 passwordHash）。
                // 用它刷新 noPassword 标记，是为了让"这台服务器没设密码"的提醒在重启后依然成立 ——
                // 只靠登录响应里的那个字段的话，重开应用提示就消失了。
                it.copy(
                    online = result.value.ok,
                    serverVersion = result.value.version,
                    auth = it.auth.copy(noPassword = result.value.needsSetup),
                )
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

// 失败后的冷却时长：后端没有失败限流/锁定，1.5 秒是"能挡住连点穷举、又不至于让人觉得卡死"的折中。
private const val FAILED_LOGIN_COOLDOWN_MS = 1_500L

// 小时 → 毫秒，用于把本地保存的到期时刻换算成"剩余几小时"来判断当时勾没勾记住
private const val MILLIS_PER_HOUR = 3_600_000L

/**
 * 把登录失败翻译成一句能上屏的中文。
 *
 * 优先用服务端原文（例："密码错误"）：用户需要知道是密码错、还是根本没连上服务器。
 * 服务端没给文案时（例如 500、网关错误）退回数据层已经写好的中文 userMessage。
 */
private fun describeLoginFailure(error: ApiError): String {
    if (error is ApiError.Http && error.serverMessage.isNotBlank()) return error.serverMessage
    return error.userMessage
}
