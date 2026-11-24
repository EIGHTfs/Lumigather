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
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.CategoryView
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.currentEpochMillis
import ui.screens.download.ImageDownloader
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
) : StateChannel {

    private val mutableState = mutableStateOf(HomeUiState())

    override val state: HomeUiState
        get() = mutableState.value

    // 唯一的写入口。集中一处写的好处：将来要加日志/埋点只改这里。
    override fun mutate(transform: (HomeUiState) -> HomeUiState) {
        mutableState.value = transform(mutableState.value)
    }

    // 侧栏子状态机：开合、选中图片、搜索词
    private val sidebar = SidebarController(this, onDetailOpened = ::handleDetailOpened)

    // 下载子状态机：进度、结果、去重、取消
    private val download = DownloadController(this, imageDownloader)

    // 设置子状态机：地址读写、连接测试、设置界面进出。
    // 两个回调是本类编排"换地址后的跨功能清理"与"重载内容"的接口。
    private val settings = SettingsController(
        channel = this,
        addressStore = addressStore,
        clientFactory = clientFactory,
        onUnauthorized = ::handleUnauthorized,
        onAddressCommitted = ::handleAddressCommitted,
        onReloadRequired = { refreshContent() },
    )

    // 登录子状态机：登录态、token 恢复、冷却、401 清理、退出登录。
    // 只给它"当前仓库"的 provider，不持有仓库本身（仓库会随换地址整体替换）。
    private val authController = AuthController(
        channel = this,
        authTokenStore = authTokenStore,
        nowProvider = nowProvider,
        repositoryProvider = { repository },
    )

    // 浏览子状态机：分类、分页、切换、重试、服务端状态线。
    // 放在最后构造：它引用的三个回调分别来自侧栏（收栏）、登录（未设密码标记）、设置（地址已可用）。
    private val browse = BrowseController(
        channel = this,
        repositoryProvider = { repository },
        onNeedsBootstrap = { bootstrap() },
        onCategorySelected = ::handleCategorySelected,
        onServerNeedsSetup = authController::applyServerNeedsSetup,
        onServerReady = settings::markAddressSaved,
    )

    // 当前生效的仓库；地址未配置时为 null（此时界面在设置态）。
    // 仓库归 facade 持有：换地址时要整体重建，各子状态机只通过 provider 拿"现在那个"。
    private var repository: LumigatherRepository? = null

    // 当前 repository 对应的地址：用来判断"地址是否真的变了"，没变就不重复重建。
    private var activeAddress: String = ""

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

    // 设置界面：地址读写、连接测试、进/出设置界面都委托给设置子状态机。
    // 进设置页要先把两侧栏收起来（设置是整屏替换），而开合状态归侧栏子状态机，所以由本类编排。
    fun openSettings() {
        sidebar.closeSidebars()
        settings.openSettings()
    }

    fun updateAddressDraft(address: String) {
        settings.updateAddressDraft(address)
    }

    suspend fun testAddress() {
        settings.testAddress()
    }

    // 保存地址并立即生效：真正的流程在设置子状态机里，跨功能清理由本类编排（见 handleAddressCommitted）
    suspend fun saveAddress() {
        settings.saveAddress()
    }

    /**
     * 地址已落盘后的跨功能清理（由设置子状态机同步回调进来）。
     *
     * 顺序即依赖：先清"属于旧服务器的凭证"，再清"属于旧服务器的数据"，最后换仓库 ——
     * 反过来的话，重建出来的仓库可能拿旧 token 去请求一次，白挨一个 401。
     * 三步之间没有挂起点，UI 看不到中间态。
     */
    private fun handleAddressCommitted(address: String) {
        // 换服务器必须清 token：旧 token 是上一台服务器签的，对新服务器一律无效，
        // 留着它的唯一后果是之后每次带凭证的请求都 401，而用户完全看不出原因。
        resetAuthForAddressChange()
        resetContentForAddressChange()
        sidebar.resetSearch()
        attachRepository(address, forceRebuild = true)
    }

    // 清登录态（含弹层与密码草稿）。拆分登录子状态机后这里改成调它。
    // 清登录态（含弹层与密码草稿）—— 换服务器专用，所以连"上一台服务器的登录界面状态"一起清
    private fun resetAuthForAddressChange() {
        authController.clearSession(closeSheet = true)
    }

    // 清浏览数据与状态栏（旧服务器的一切）：数据归浏览，选中图片归侧栏
    private fun resetContentForAddressChange() {
        browse.resetContent()
        sidebar.clearSelectedImage()
    }

    // 切换分类：具体流程（分页重置、首屏重拉）在浏览子状态机里；收侧栏由它回调本类编排
    suspend fun selectCategory(categoryName: String) {
        browse.selectCategory(categoryName)
    }

    // 分页加载：到底判据与游标推进都在浏览子状态机里
    suspend fun loadMore() {
        browse.loadMore()
    }

    // 重试：没配置就回设置态；分类没拿到就重跑主链路；只是图片页失败就只重拉当前页
    suspend fun retry() {
        browse.retry()
    }

    /**
     * 分类被点了之后要收哪些侧栏（由浏览子状态机回调进来）。
     *
     * @param switched true=真的换了分类 → 两侧栏都收、选中图清掉（旧图不属于新分类）；
     *                 false=只是又点了当前分类 → 只收左栏，右侧详情保持不动。
     */
    private fun handleCategorySelected(switched: Boolean) {
        if (switched) {
            sidebar.closeAndClearSelection()
        } else {
            sidebar.closeLeftSidebar()
        }
    }

    // 侧栏开合与选中项：委托给侧栏子状态机
    fun openLeftSidebar() {
        sidebar.openLeftSidebar()
    }

    fun openRightSidebar() {
        sidebar.openRightSidebar()
    }

    fun openDetail(image: ImageItem) {
        sidebar.openDetail(image)
    }

    fun closeSidebars() {
        sidebar.closeSidebars()
    }

    fun updateSearchQuery(query: String) {
        sidebar.updateSearchQuery(query)
    }

    // 点开一张新图后：清掉上一张的下载结果与提示，否则用户会以为新图"已保存到相册"。
    // 由侧栏子状态机回调进来，具体怎么复位归下载子状态机决定。
    private fun handleDetailOpened() {
        download.resetForImageChange()
    }

    // 下载详情图：委托给下载子状态机（进度/结果/去重/取消都在它那里）
    suspend fun downloadSelectedImage() {
        download.downloadSelectedImage()
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

    // ---- 登录底座：全部委托给登录子状态机 ----

    fun openLoginSheet() {
        authController.openLoginSheet()
    }

    fun closeLoginSheet() {
        authController.closeLoginSheet()
    }

    fun updateLoginPassword(password: String) {
        authController.updateLoginPassword(password)
    }

    fun toggleLoginRemember(remember: Boolean) {
        authController.toggleLoginRemember(remember)
    }

    // 提交登录：校验/远端调用/失败冷却/成功落盘 token 都在登录子状态机里
    suspend fun submitLogin() {
        authController.submitLogin()
    }

    // 退出登录：先尽力通知服务端，再无条件清本地 —— 本地退不掉会把用户困在登录态
    suspend fun logout() {
        authController.logout()
    }

    // 读本地会话：过期/格式异常一律当未登录（并清掉坏值）
    private fun restoreAuthSession() {
        authController.restoreSession()
    }

    // 401 回调（ApiClient 在非登录请求收到 401 时调一次）：清 token + 明确提示重新登录
    private fun handleUnauthorized() {
        authController.handleUnauthorized()
    }

    // 从设置界面退回主界面。没配过地址时不允许退：退出去也没有内容可看。
    fun closeSettings() {
        settings.closeSettings()
    }

    private fun enterSettingsMode(hasSavedAddress: Boolean) {
        settings.enterSettingsMode(hasSavedAddress)
    }

    // 地址变了才重建仓库：重复重建会白丢连接池与已缓存的分类。
    private fun attachRepository(address: String, forceRebuild: Boolean = false) {
        if (!forceRebuild && repository != null && activeAddress == address) return
        repository = LumigatherRepository(clientFactory(address, ::handleUnauthorized))
        activeAddress = address
    }

    // 地址落定之后的主链路：分类 → 第一页 → 后台补张数（全部在浏览子状态机里）
    private suspend fun refreshContent() {
        browse.refreshContent()
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
