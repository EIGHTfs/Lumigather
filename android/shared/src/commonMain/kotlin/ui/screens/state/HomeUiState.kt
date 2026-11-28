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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ArchiveHeuristics
import com.eightfs.lumigather.data.AppUpdateClient
import com.eightfs.lumigather.data.AppUpdatePlatform
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.CategoryView
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.MediaSrc
import com.eightfs.lumigather.data.createAppUpdatePlatform
import com.eightfs.lumigather.data.currentEpochMillis
import ui.screens.createDefaultHttpTransport
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
 * 当前"在看什么"：某个游戏分类 / 收藏 / 游客上传。
 *
 * 为什么是**一个值**而不是「视图类型 + 选中分类」两个字段：
 * 两个字段要靠人工保持同步，任何一条切换路径漏改一处，界面就会停在
 * 「视图是收藏、分类是刚点的那个」这种半状态（实测过的 bug：选中收藏后点分类切不过去）。
 * 单值模型下一个变量只能是一个值，互斥天然成立，不存在"记得同时改"这回事。
 *
 * 刻意不设 All 变体：本项目左栏没有"全部概览"入口，任何时候总有一个具体内容在看；
 * 再加一个 All 就又多出一个需要与"选中分类"对齐的第三态，等于把问题搬回去。
 */
sealed interface ContentFilter {
    data class Category(val name: String) : ContentFilter
    data object Favorites : ContentFilter
    data object Guest : ContentFilter
}

/**
 * 网格里的一个条目：图 / 文件夹 / 压缩包。
 *
 * 为什么要这层抽象：网格内容的消费方（渲染、空态判断、点当前项判重）只关心"有多少条目"，
 * 不关心它是图还是目录还是包。抽成同质条目后，新增"文件夹"和"压缩包"只改产出侧（状态层）
 * 与渲染侧的一个 when，而不是在十几处判断"这一项是不是目录"。
 */
sealed interface GridEntry {
    /** 一张图：本层直接含的图 / 叶子目录摊开的图 / 压缩包里的图，UI 不需要区分来源。 */
    data class Picture(val item: ImageItem) : GridEntry

    /** 一个还有子目录的文件夹（count < 0 表示张数未知，界面显示「目录」）。 */
    data class Folder(val name: String, val path: String, val count: Int) : GridEntry

    /** 一个含图的压缩包（空包不产出）。 */
    data class Archive(val name: String, val path: String, val imageCount: Int) : GridEntry
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
    // 唯一的内容筛选值。初始 Category("") = "分类还没加载出来"，由浏览子状态机在
    // 拉到分类后立刻换成第一个分类的名字
    val filter: ContentFilter = ContentFilter.Category(""),
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
    // ---- 文件夹层级浏览 ----
    // 当前这一层由 /api/dir 产出的条目（图 + 子目录）。**目录栈不在这里**：
    // 栈只在浏览子状态机内部，界面只拿到渲染所需的条目与面包屑文本
    val dirEntries: List<GridEntry> = emptyList(),
    // 面包屑：第一段恒为分类名，之后每段对应一层目录。点第 i 段 = 跳回第 i 层
    val breadcrumbs: List<String> = emptyList(),
    // 能否返回上级（根目录为 false）。/api/dir 不返回 canUp，由栈深度决定
    val canGoUp: Boolean = false,
    // 已展开的压缩包（null = 在目录层）。展开后网格内容变成包内图片
    val openedArchive: ArchiveEntry? = null,
    val archiveImages: List<ImageItem> = emptyList(),
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
    // ---- 收藏 / 游客数据（展示哪一套由 filter 决定，不另设开关）----
    val favoriteImages: List<ImageItem> = emptyList(),
    // 收藏条目的规范化 src 集合：判断"当前这张图收没收藏"用它（O(1)，且不依赖列表顺序）
    val favoriteSrcs: Set<String> = emptySet(),
    val guestImages: List<ImageItem> = emptyList(),
    val favoritesLoading: Boolean = false,
    val guestLoading: Boolean = false,
    // 正在添加/移除的那条 src（非空 = 操作中 → 按钮禁用，防连点）
    val favoriteActionSrc: String = "",
    // ---- 压缩包 / 序列帧：属于"当前目录内容"，不是独立视图 ----
    // 扫描的是哪个目录（= 当前分类的 rootPath）；换目录/换服务器都要清
    val archiveDir: String = "",
    val archives: List<ArchiveEntry> = emptyList(),
    val archivesLoading: Boolean = false,
    // 扫描失败的原因（独立字段，不占用那一行全局提示）：扫包是次要功能，
    // 它失败绝不能盖掉"登录已过期""下载失败"这类更重要、且与用户当前动作直接相关的提示
    val archiveScanError: String? = null,
    // 已收起的包（按 path 记）。放在状态里而不是组件内：切分类再切回来要保留用户的折叠选择
    val collapsedArchives: Set<String> = emptySet(),
    // 每个包自己的 GIF 状态（key = 包 path）。包级而不是全局单值：
    // 一个目录下可能同时有多个序列帧包，各自独立生成、独立失败
    val zipGifStates: Map<String, GifState> = emptyMap(),
    // ---- 应用更新（与服务器无关：走 GitHub Releases，未登录也能查）----
    // 失败/需授权都在这一个字段里，刻意不写 actionHint：检查更新是次要动作，
    // 它的失败不能盖掉「登录已过期」这类与用户当前操作直接相关的提示
    val update: UpdateUiState = UpdateUiState.Idle,
    // 当前安装的版本名（启动时读一次；取不到就是空串，界面显示"版本未知"）
    val localVersionName: String = "",
) {
    // 左侧栏的可见分类：搜索框有内容时按展示名过滤，空查询不过滤。
    val visibleCategories: List<CategoryView>
        get() {
            val keyword = searchQuery.trim()
            if (keyword.isEmpty()) return categories
            return categories.filter { it.label.contains(keyword, ignoreCase = true) }
        }

    /**
     * 当前选中的分类名（筛选值不是分类时给 null）。
     *
     * 这是"分类"这个概念在状态里的**唯一出口**：分页、顶栏标题、切分类的判重都从这里取名，
     * 不再有第二个字段可以与之不一致。
     */
    val currentCategoryName: String?
        get() = (filter as? ContentFilter.Category)?.name?.takeIf { it.isNotEmpty() }

    val selectedCategoryView: CategoryView?
        get() = currentCategoryName?.let { name -> categories.firstOrNull { it.name == name } }

    // 是否"按分类看内容"：分类加载态、分页哨兵这些只对分类视图有意义
    val isCategoryFilter: Boolean
        get() = filter is ContentFilter.Category

    // 底部状态栏文案。连上了还要区分"服务端自报 ok=false"（进程活着但状态异常）。
    val statusText: String
        get() = if (online) "在线" else "离线"

    // 顶栏副标题：随视图切换 —— 分类视图给"分类名 · 已加载/总数"，收藏/游客给"名称 · 条数"
    val contentSubtitle: String
        get() = when (filter) {
            is ContentFilter.Category -> {
                val categoryName = selectedCategoryView?.label.orEmpty()
                when {
                    categoryName.isEmpty() -> "正在读取分类"
                    totalCount <= 0 -> categoryName
                    else -> "$categoryName · ${loadedCount}/${totalCount}"
                }
            }

            ContentFilter.Favorites -> "收藏 · ${favoriteImages.size}"
            ContentFilter.Guest -> "游客 · ${guestImages.size}"
        }

    // ---- 三视图的唯一出口：页面只读这几个派生值，不再自己判断 view ----

    /**
     * 网格内容：**唯一的网格数据出口**。
     *
     * 分类视图 = 本层条目（图/文件夹）+ 含图压缩包；展开了某个包 = 包内图片；
     * 收藏/游客视图 = 一堆图片。三处都产出 [GridEntry.Picture]，所以渲染侧完全不知道
     * 内容来自哪里 —— 这就是"新增文件夹不牵动 UI"的落点。
     */
    val gridEntries: List<GridEntry>
        get() = when (filter) {
            is ContentFilter.Category -> {
                val opened = openedArchive
                if (opened != null) {
                    archiveImages.map { image -> GridEntry.Picture(image) }
                } else {
                    dirEntries + visibleArchives.map { entry ->
                        GridEntry.Archive(entry.name, entry.path, entry.images.size)
                    }
                }
            }

            ContentFilter.Favorites -> favoriteImages.map { image -> GridEntry.Picture(image) }
            ContentFilter.Guest -> guestImages.map { image -> GridEntry.Picture(image) }
        }

    val gridLoading: Boolean
        get() = when (filter) {
            is ContentFilter.Category -> isLoadingPage
            ContentFilter.Favorites -> favoritesLoading
            ContentFilter.Guest -> guestLoading
        }

    // 空态文案：三套数据"为什么没有"的原因不同，用同一句会让人以为收藏坏了
    val gridEmptyTitle: String
        get() = when (filter) {
            is ContentFilter.Category -> "这个分类还没有图片"
            ContentFilter.Favorites -> "还没有收藏的图片"
            ContentFilter.Guest -> "还没有游客上传的图片"
        }

    val gridEmptyHint: String
        get() = when (filter) {
            is ContentFilter.Category -> "试试左上角换一个分类"
            ContentFilter.Favorites -> "在图片详情里点「收藏」，就会出现在这里"
            ContentFilter.Guest -> "游客上传区目前是空的"
        }

    // 当前详情图是否已收藏：按规范化 src 比对（原始写法可能是完整 URL，直接比会永远不相等）
    val isCurrentImageFavorite: Boolean
        get() {
            val image = selectedImage ?: return false
            return MediaSrc.normalize(image.relativeUrl) in favoriteSrcs
        }

    // ---- 压缩包区块（当前目录内容的第二部分，挂在图片网格之后）----

    /**
     * 要渲染的包：只保留有图的（服务端可能给出只有 ini / 只有非图片条目的包，
     * 那种包点开是空的，占着位置只会让人以为界面坏了）。
     */
    val visibleArchives: List<ArchiveEntry>
        get() = archives.filter { it.images.isNotEmpty() }

    /**
     * 真·空目录判定：图片与压缩包都空才算空。
     *
     * 分类下有包但没图（Pixiv 图包常见）时不能显示"这个分类还没有图片" —— 内容其实就在下面。
     */
    val isContentEmpty: Boolean
        get() = gridEntries.isEmpty()

    /**
     * 这个包是否值得合成动图（决定包内显示"GIF 卡"还是"平铺图片"）。
     *
     * 判据只是"条目名像不像序列帧"，真正能不能合成由服务端决定；
     * 判错最多是显示了一张会失败的卡，不会给出错误结果。
     */
    fun canSynthGif(entry: ArchiveEntry): Boolean =
        ArchiveHeuristics.looksLikeSequence(entry.images)

    // 某个包的折叠态与 GIF 状态（按包 path 索引，互不影响）
    fun isArchiveCollapsed(entry: ArchiveEntry): Boolean = entry.path in collapsedArchives

    fun gifStateOf(entry: ArchiveEntry): GifState = zipGifStates[entry.path] ?: GifState.Idle

    // 当前这张图是否正在增删（按钮禁用 + 文案改"处理中"）
    val isFavoriteMutating: Boolean
        get() {
            val image = selectedImage ?: return false
            return favoriteActionSrc.isNotEmpty() &&
                favoriteActionSrc == MediaSrc.normalize(image.relativeUrl)
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
    // 更新相关的两个依赖默认走真实实现（GitHub + 系统 API）；单测注入假实现来验
    // "版本比较 / 哈希不匹配 / 签名不匹配 / 网络失败"这些现实中难以复现的分支
    private val appUpdateClientProvider: (() -> AppUpdateClient)? = null,
    private val appUpdatePlatformProvider: (() -> AppUpdatePlatform)? = null,
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
        onReloadRequired = { reloadAfterAddressChange() },
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
        onServerNeedsSetup = authController::applyServerNeedsSetup,
        onServerReady = settings::markAddressSaved,
    )

    // 压缩包子状态机：扫描压缩包、打开包内图片、合成序列帧 GIF（全部走服务端）
    private val archive = ArchiveController(
        channel = this,
        repositoryProvider = { repository },
    )

    // 更新子状态机：查 GitHub 最新发布 → 下载 → 校验 → 调起安装器。
    // 它不依赖登录态、也不依赖已保存的服务器地址（连接服务器页在未连上时就要能查更新）。
    private val updateController = UpdateController(
        channel = this,
        clientProvider = {
            appUpdateClientProvider?.invoke()
                ?: updateClient?.let { it }
                ?: AppUpdateClient(createDefaultHttpTransport()).also { updateClient = it }
        },
        platformProvider = { appUpdatePlatformProvider?.invoke() ?: createAppUpdatePlatform() },
    )
    private var updateClient: AppUpdateClient? = null

    init {
        // 版本号只需读一次：装完新包是新的进程，不存在"运行中版本变化"
        val installedVersionName = updateController.localVersionName()
        if (installedVersionName.isNotEmpty()) {
            mutate { it.copy(localVersionName = installedVersionName) }
        }
    }

    // 收藏子状态机：收藏/游客列表、增删、是否已收藏、失败提示。
    // token 与"未登录就弹登录"都通过回调取得，自己不碰登录逻辑。
    private val favorite = FavoriteController(
        channel = this,
        repositoryProvider = { repository },
        tokenProvider = { authTokenStore.read()?.token.orEmpty() },
        onRequestLogin = authController::openLoginSheet,
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
        // 收藏与游客列表是游客可见的公共数据，启动时顺手拉一次：左栏两个入口要显示数量，
        // 不拉的话用户会看到"收藏 0"以为自己的收藏丢了
        favorite.loadFavorites()
        favorite.loadGuestUploads()
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
        // 收藏/游客列表也属于"旧服务器的数据"：不清的话图标、计数、可点性全是假的
        favorite.resetLists()
        // 压缩包路径、包内图片、已合成的 GIF 同理：留着每条都点不开
        archive.resetArchive()
        sidebar.resetSearch()
        // 筛选回"空分类"：新服务器上原来停在收藏页多半是空的，停在空页会让人以为收藏丢了；
        // 置成空分类后，接下来重拉分类时会被浏览子状态机换成第一个分类
        mutate { it.copy(filter = ContentFilter.Category("")) }
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

    /**
     * 唯一的内容切换入口：左栏的分类行与「收藏 / 游客」行都调它。
     *
     * 只接受一个筛选值，所以"切了一半"不可能发生 —— 这就是把 view + selectedCategory
     * 两个字段合并的真实收益，而不是"补一句记得同时改"。
     *
     * 重复点当前项时只收左栏、不重复拉数据（用户点它是想收起列表看结果）。
     */
    suspend fun selectFilter(target: ContentFilter) {
        // 判据：点的就是当前筛选，且这份数据已经有内容 → 只收左栏
        if (target == state.filter && state.gridEntries.isNotEmpty()) {
            sidebar.closeLeftSidebar()
            return
        }

        // 换筛选：两侧栏都收、清掉选中图（旧图不属于新内容），提示也一并清掉
        mutate {
            it.copy(
                filter = target,
                leftSidebarOpen = false,
                rightSidebarOpen = false,
                selectedImage = null,
                actionHint = null,
            )
        }

        when (target) {
            is ContentFilter.Category -> loadCategoryContent(target.name)
            ContentFilter.Favorites -> favorite.loadFavorites()
            ContentFilter.Guest -> favorite.loadGuestUploads()
        }
    }

    /**
     * 加载某个分类的内容：图片列表与压缩包扫描**并行**发起。
     *
     * 为什么并行：扫包要走服务端 spawn 7zz 列目录，明显比图片列表慢；两者互不依赖，
     * 串行会让网格白等。谁先回来谁先渲染。
     */
    private suspend fun loadCategoryContent(categoryName: String) {
        val rootPath = state.categories.firstOrNull { it.name == categoryName }?.rootPath.orEmpty()
        coroutineScope {
            launch { browse.loadCategory(categoryName) }
            launch { archive.loadArchives(rootPath) }
        }
    }

    // 分页加载：到底判据与游标推进都在浏览子状态机里
    suspend fun loadMore() {
        browse.loadMore()
    }

    // 重试：没配置就回设置态；分类没拿到就重跑主链路；只是图片页失败就只重拉当前页
    suspend fun retry() {
        browse.retry()
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
     * 收藏 / 取消收藏当前详情图（真接口）。
     *
     * 未登录时由收藏子状态机回调登录弹层入口 —— 登录判断只有一份，不在这里重复写。
     */
    suspend fun toggleFavorite() {
        favorite.toggleFavorite()
    }

    // ---- 应用更新：全部转发给更新子状态机 ----

    /** 当前安装版本名（界面显示「当前版本 x.y.z」）。 */
    fun localVersionName(): String = updateController.localVersionName()

    /** 检查更新。检查中/下载中重复调用会被忽略。 */
    suspend fun checkUpdate() {
        updateController.check()
    }

    /** 下载并安装（仅在发现有新版本时有效）。 */
    suspend fun downloadAndInstallUpdate() {
        updateController.downloadAndInstall()
    }

    /** 取消下载：界面回到「可再次下载」，不报错。 */
    fun cancelUpdate() {
        updateController.cancel()
    }

    /** 丢弃更新区状态（换服务器/重置时用；下载中的任务由调用方取消）。 */
    fun resetUpdate() {
        updateController.reset()
    }

    // ---- 文件夹层级导航 ----
    //
    // 目录栈在浏览子状态机内部，这里只按"目标层级"编排两件事：加载该层目录内容、
    // 并行扫该层的压缩包。两者互不依赖，串行会让用户多点一次等两个 RTT。

    /** 进子文件夹。 */
    suspend fun enterFolder(name: String) {
        val targetPath = (state.breadcrumbs.drop(1) + name).joinToString("/")
        loadLayerAndArchives(targetPath) { browse.enterFolder(name) }
    }

    /** 返回上级（根目录时无操作）。 */
    suspend fun goUp() {
        val parentSegments = state.breadcrumbs.drop(1).dropLast(1)
        val targetPath = parentSegments.joinToString("/")
        loadLayerAndArchives(targetPath) { browse.goUp() }
    }

    /**
     * 跳回面包屑第 index 段（0 = 分类根）。
     *
     * 段号与 pathStack 深度一一对应：面包屑 = [分类名] + 栈，所以 index 就是目标深度。
     */
    suspend fun jumpToCrumb(index: Int) {
        val segments = state.breadcrumbs.drop(1)
        val targetPath = segments.take(index.coerceIn(0, segments.size)).joinToString("/")
        loadLayerAndArchives(targetPath) { browse.jumpTo(index) }
    }

    /** 展开某个压缩包（网格切成包内图片；返回上级回到目录层）。 */
    fun openArchive(path: String) {
        archive.openArchive(path)
    }

    /** 从包内视图回到目录层。 */
    fun closeArchiveView() {
        archive.closeArchive()
    }

    private suspend fun loadLayerAndArchives(targetRelativePath: String, loadLayer: suspend () -> Unit) {
        val absoluteDir = absDirPath(targetRelativePath)
        coroutineScope {
            launch { loadLayer() }
            launch { archive.loadArchives(absoluteDir) }
        }
    }

    // 相对路径 → 绝对路径（分类根来自当前分类；根目录就是分类根本身）
    private fun absDirPath(relativePath: String): String {
        val root = state.selectedCategoryView?.rootPath.orEmpty()
        if (root.isEmpty()) return ""
        return if (relativePath.isEmpty()) root else "$root/$relativePath"
    }

    // ---- 压缩包 / 序列帧：全部转发给压缩包子状态机 ----

    /** 折叠/展开某个包（收起状态留在状态里，切分类回来还保留）。 */
    fun toggleArchiveCollapsed(zipPath: String) {
        archive.toggleCollapsed(zipPath)
    }

    /**
     * 请求合成某个包的 GIF。生成中重复调用会被忽略（防连点、防自动触发重复发请求）。
     */
    fun requestZipGif(zipPath: String) {
        archive.requestGif(zipPath)
    }

    // 图片加载成功/失败回调：GIF 是"按 URL 加载"的，只有图片层知道完成没完成
    fun onZipGifLoaded(zipPath: String, url: String) {
        archive.onGifLoaded(zipPath, url)
    }

    suspend fun onZipGifLoadFailed(zipPath: String, url: String) {
        archive.onGifLoadFailed(zipPath, url)
    }

    /** 丢弃某个包的 GIF（失败重试 / 换包时复位）。 */
    fun resetZipGif(zipPath: String) {
        archive.resetGif(zipPath)
    }

    /** 把某个包内的条目名拼成可加载的图片模型（UI 直接用，不自己拼 URL）。 */
    fun zipImagesOf(entry: ArchiveEntry): List<ImageItem> = archive.zipImagesOf(entry)

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
        // 初次加载要等浏览子状态机选出第一个分类才知道目录，所以这里是"分类落定后立刻扫"；
        // 用户切分类时走 selectFilter()，那条路径是真并行。
        archive.loadArchives(state.selectedCategoryView?.rootPath.orEmpty())
    }

    // 换服务器后要把"所有来自服务器的数据"重新拉一遍：分类图之外还有收藏与游客上传
    private suspend fun reloadAfterAddressChange() {
        refreshContent()
        favorite.loadFavorites()
        favorite.loadGuestUploads()
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
