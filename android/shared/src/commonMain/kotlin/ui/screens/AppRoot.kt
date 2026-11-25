// 三栏骨架的根：主界面常驻，两侧栏从边缘滑出，中间那层遮罩负责"点一下收起"。
// 未配置服务器地址时，这里直接渲染设置界面（此时三栏骨架不参与组合）。
//
// 为什么位移/缩放/模糊都集中在这一个文件：这三件事必须**同时**发生才像"被推开"——
// 主界面右移、缩小、背景模糊、遮罩变暗、侧栏滑入。分散到各页面里写，就会出现
// "侧栏动了主界面没动"这种半截效果。它们读到的是同一个进度值（0..1），所以天然同步。
//
// 版面分三层（自下而上）：主界面 → 遮罩 → 侧栏。侧栏在遮罩之上，所以侧栏本身不被压暗。

package ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.createAuthTokenStore
import com.eightfs.lumigather.data.currentEpochMillis
import com.tencent.kuikly.compose.BackHandler
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.BlurEffect
import com.tencent.kuikly.compose.ui.graphics.Brush
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import theme.AppTheme
import theme.LumigatherTheme
import theme.ThemeMode
import ui.screens.auth.LoginSheet
import ui.screens.download.ImageDownloader
import ui.screens.download.createImageDownloader
import ui.screens.main.HomeScreen
import ui.screens.settings.ServerAddressStore
import ui.screens.settings.ServerSettingsScreen
import ui.screens.settings.createServerAddressStore
import ui.screens.sidebar.LeftSidebar
import ui.screens.sidebar.RightSidebar
import ui.screens.state.HomeStateHolder
import ui.screens.state.HomeUiState

// 页面名：:app 侧打开页面时用它（也是 KSP 生成的页面注册表里的键）。
// 公开而不是私有：宿主那边要引用同一个常量，两处各写一份字符串迟早会漂移，
// 而漂移的后果是"装上去只看到空白页"，很难一眼看出是拼写问题。
const val HOME_PAGE_NAME = "LumigatherHome"

/**
 * 传输层工厂的平台声明。
 *
 * commonMain 看不到 java.* 与 Android API，所以具体用哪个 HTTP 库只能由平台侧给。
 * 声明成 expect 而不是在页面里 `if (平台) ...`，好处是"缺实现"会变成编译错误而不是运行时空白。
 */
expect fun createDefaultHttpTransport(): HttpTransport

/**
 * 根组合：状态在设置态就画设置界面，否则画三栏骨架；登录弹层在两种状态下都能弹出来。
 *
 * @param clientFactory   按地址造 ApiClient（第二个参数是 401 回调）。改地址后需要换掉整个
 *                        ApiClient，所以传工厂而不是实例
 * @param addressStore    地址持久化入口（真机上是 SharedPreferences）
 * @param imageDownloader 存相册能力（真机上是 MediaStore 实现）。注入而不是页面自己 new，
 *                        是为了让"下载成功/失败"能在状态层被测到，不必真的往相册写文件
 * @param authTokenStore  会话 token 持久化入口（真机上与地址共用同一个 SharedPreferences 文件）
 * @param nowProvider     时钟（注入以便测试构造"token 已过期"）
 */
@Composable
fun AppRoot(
    clientFactory: (String, () -> Unit) -> ApiClient,
    addressStore: ServerAddressStore,
    imageDownloader: ImageDownloader,
    authTokenStore: AuthTokenStore,
    modifier: Modifier = Modifier,
    nowProvider: () -> Long = { currentEpochMillis() },
    stateHolder: HomeStateHolder = remember(clientFactory, addressStore, imageDownloader, authTokenStore) {
        HomeStateHolder(clientFactory, addressStore, imageDownloader, authTokenStore, nowProvider)
    },
) {
    val scope = rememberCoroutineScope()

    // 读 state 的字段即订阅：任何 mutate 都会让下面重画
    val state = stateHolder.state

    // 启动链路只跑一次：读地址 → 恢复登录态 →（没配就进设置态）分类 → 第一页 → 后台补张数
    LaunchedEffect(stateHolder) { stateHolder.bootstrap() }

    registerBackHandling(state = state, stateHolder = stateHolder)

    if (state.settingsOpen) {
        ServerSettingsScreen(
            state = state,
            onAddressChange = stateHolder::updateAddressDraft,
            onTestConnection = { scope.launch { stateHolder.testAddress() } },
            onSave = { scope.launch { stateHolder.saveAddress() } },
            onLogin = stateHolder::openLoginSheet,
            onLogout = { scope.launch { stateHolder.logout() } },
            onClose = stateHolder::closeSettings,
            modifier = modifier,
        )
    } else {
        // 下载用 AppRoot 自己的作用域而不是 MainScaffold 的：下载是一次有副作用的写文件动作，
        // 半路被取消会留下未完成的相册条目。而 MainScaffold 会随"进设置界面"被移出组合，
        // 用它自己的作用域就等于把"用户点开设置"变成"取消下载"。
        MainScaffold(
            state = state,
            stateHolder = stateHolder,
            actionScope = scope,
            modifier = modifier,
        )
    }

    // 登录弹层放在两种界面**之外**：设置页的「登录」与详情页的「收藏」都会打开它，
    // 放进去任何一个分支里都会漏掉另一种入口。
    LoginSheet(
        visible = state.loginSheetOpen,
        password = state.loginPassword,
        rememberDevice = state.loginRemember,
        loginState = state.loginState,
        canSubmit = state.loginSubmittable,
        onPasswordChange = stateHolder::updateLoginPassword,
        onRememberChange = stateHolder::toggleLoginRemember,
        onSubmit = { scope.launch { stateHolder.submitLogin() } },
        onDismiss = stateHolder::closeLoginSheet,
        // 服务端没设密码时，弹层要告诉用户"空密码也能登"，否则会对着输入框猜该填什么
        noPasswordServer = state.auth.noPassword,
    )
}

/**
 * 返回键分级处理。
 *
 * 规则：登录弹层开着 → 关弹层；设置界面（且已有旧配置）→ 退回主界面；侧栏开着 → 收起侧栏；
 * 都没有 → 什么都不注册。
 *
 * 为什么"没有就不注册"是硬要求：Kuikly 判断一次返回是否被消费，依据是页面返回回调列表是否非空
 * （Pager 收到 back 事件时先看 getBackPressHandler().backPressCallbackList，非空才回
 * consumed=1 并分发给我们）。常驻注册会让每次返回都被消费，用户就再也退不出应用。
 *
 * 顺序也是要求：弹层盖在最上面，返回就该先关它，而不是把底下的设置页退掉。
 */
@Composable
private fun registerBackHandling(state: HomeUiState, stateHolder: HomeStateHolder) {
    when {
        state.loginSheetOpen -> BackHandler { stateHolder.closeLoginSheet() }
        state.settingsOpen && state.hasSavedAddress -> BackHandler { stateHolder.closeSettings() }
        state.settingsOpen -> Unit
        state.leftSidebarOpen || state.rightSidebarOpen -> BackHandler { stateHolder.closeSidebars() }
        else -> Unit
    }
}

// 三栏骨架本体：主界面 + 遮罩 + 左右侧栏 + 左边缘手势。
@Composable
private fun MainScaffold(
    state: HomeUiState,
    stateHolder: HomeStateHolder,
    actionScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()

    // 手势拖动中的实时进度；null 表示没有手指在拖，此时交给吸附动画。
    // 这两条状态是「跟手」的唯一开关：非 null 时 animatedProgress 直接返回它的值。
    var leftDragProgress by remember { mutableStateOf<Float?>(null) }
    var rightDragProgress by remember { mutableStateOf<Float?>(null) }

    // 两个侧栏各自的动画进度。左右用同一条曲线与时长，手感才一致。
    val leftProgress = animatedProgress(state.leftSidebarOpen, leftDragProgress)
    val rightProgress = animatedProgress(state.rightSidebarOpen, rightDragProgress)
    // 任一侧栏打开都要把主界面推开并压暗，所以取两者的较大值作为"整体打开程度"。
    val openProgress = if (leftProgress > rightProgress) leftProgress else rightProgress

    // 侧栏跟手拖动：挂在网格区而不是整屏。
    // 挂整屏会把顶部 chip 条（LazyRow）的横向滚动一起吃掉 —— 它滚不动，用户就选不了分类。
    val gridDragModifier = Modifier.sidebarDragGesture(
        enabled = !state.leftSidebarOpen && !state.rightSidebarOpen,
        onLeftProgress = { leftDragProgress = it },
        onRightProgress = { rightDragProgress = it },
        onCommitLeft = { stateHolder.openLeftSidebar() },
        onCommitRight = { stateHolder.openRightSidebar() },
        onDragFinished = {
            leftDragProgress = null
            rightDragProgress = null
        },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 页面背景用竖向渐变（顶部偏蓝、底部偏白），纯色会让整屏显得"糊成一片"。
            // 端点取自主题语义色，不在这里写死色值。
            .background(
                Brush.verticalGradient(
                    listOf(colors.backgroundGradientTop, colors.backgroundGradientBottom),
                ),
            ),
    ) {
        // 第一层：主界面。位移方向由"哪一侧打开"决定：左栏开则右移，右栏开则左移。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 侧栏拖动**不挂在这一层**：这一层覆盖整个主界面，会把顶部 chip 条的
                // 横向滚动一起吃掉（Kuikly 的 detectHorizontalDragGestures 用
                // awaitFirstDown(requireUnconsumed=false) + consume()，子组件收不到事件）。
                // 改为下沉到 HomeScreen 的网格区，见下方 gridGestureModifier。
                .graphicsLayer {
                    val scale = 1f - HomeLayout.mainScaleDelta * openProgress
                    scaleX = scale
                    scaleY = scale
                    translationX = (leftProgress - rightProgress) * HomeLayout.sidebarWidth.toPx()
                    // 模糊只加在"背后的主界面"上：这一层自己糊了，看起来才是遮罩后面糊了。
                    renderEffect = if (openProgress > 0f) {
                        val radius = HomeLayout.scrimBlurRadius.toPx() * openProgress
                        BlurEffect(radius, radius)
                    } else {
                        null
                    }
                },
        ) {
            HomeScreen(
                state = state,
                gridGestureModifier = gridDragModifier,
                onOpenLeftSidebar = stateHolder::openLeftSidebar,
                onSelectCategory = { categoryName -> scope.launch { stateHolder.selectCategory(categoryName) } },
                onOpenImage = stateHolder::openDetail,
                onLoadMore = { scope.launch { stateHolder.loadMore() } },
                onRetry = { scope.launch { stateHolder.retry() } },
                onOpenSettings = stateHolder::openSettings,
                onOpenArchive = stateHolder::openArchive,
                onCloseArchive = stateHolder::closeArchive,
                onComposeGif = stateHolder::requestGif,
                onGifLoaded = stateHolder::onGifLoaded,
                // 失败要问服务端原因（一次网络请求），所以走挂起 + actionScope
                onGifLoadFailed = { url -> actionScope.launch { stateHolder.onGifLoadFailed(url) } },
                onDismissGif = stateHolder::dismissGif,
            )
        }

        // 第二层：遮罩。点它收起侧栏；透明度跟着进度走，滑出过程中不会有"突然变暗"。
        if (openProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.shadow.copy(alpha = HomeLayout.scrimMaxAlpha * openProgress))
                    .clickable(onClick = stateHolder::closeSidebars),
            )
        }

        // 第三层左：分类栏。进度 0 时整体左移一个栏宽，等于藏在屏幕外。
        if (leftProgress > 0f) {
            LeftSidebar(
                state = state,
                onSelectCategory = { categoryName -> scope.launch { stateHolder.selectCategory(categoryName) } },
                onSelectView = { view -> scope.launch { stateHolder.selectView(view) } },
                onQueryChange = stateHolder::updateSearchQuery,
                onClose = stateHolder::closeSidebars,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(HomeLayout.sidebarWidth)
                    .fillMaxHeight()
                    // Dp 只定义了「Dp * Float」，所以宽度写在前面，比例写在后面
                    .offset(x = HomeLayout.sidebarWidth * (leftProgress - 1f)),
            )
        }

        // 第三层右：详情栏。镜像逻辑：进度 0 时整体右移一个栏宽。
        if (rightProgress > 0f) {
            RightSidebar(
                state = state,
                onDownload = { actionScope.launch { stateHolder.downloadSelectedImage() } },
                // 收藏是真接口：走挂起方法，用 actionScope（不随设置页切换被取消）
                onFavorite = { actionScope.launch { stateHolder.toggleFavorite() } },
                onClose = stateHolder::closeSidebars,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(HomeLayout.sidebarWidth)
                    .fillMaxHeight()
                    .offset(x = HomeLayout.sidebarWidth * (1f - rightProgress)),
            )
        }

        // 边缘滑动已改为主界面全屏接管（见上方 Box 的 pointerInput），
        // 原「左边缘 20dp 窄热区」已移除 —— 那条缝要求手指精确落点，真机上很难触发。
    }
}

/**
 * Kuikly 页面入口。
 *
 * 只做装配：造传输层与存储、把 clientFactory 交给根组合。这里不写布局，也不碰业务状态。
 * 服务端地址不经过宿主传入 —— 它由用户填写并持久化，宿主无权替用户决定。
 */
@Page(HOME_PAGE_NAME)
class LumigatherHomePage : ComposeContainer() {

    override fun willInit() {
        super.willInit()

        setContent {
            // 传输层全局共享一份：OkHttp 的连接池与线程池就是为共享设计的，
            // 改地址时只换 ApiClient，不重建传输层。
            val transport = remember { createDefaultHttpTransport() }
            val addressStore = remember { createServerAddressStore() }
            // 相册下载器同样只需一份：它内部持有自己的线程池与查重逻辑
            val imageDownloader = remember { createImageDownloader() }
            // 会话 token 存储：与地址同一个 SharedPreferences 文件，键分开
            val authTokenStore = remember { createAuthTokenStore() }
            // 工厂必须 remember：lambda 每次重组都是新对象，会让 stateHolder 跟着重建、
            // bootstrap 反复重跑（表现是首屏反复转圈）。
            // 第二个参数把 401 回调接到 ApiClient 上：凭证失效在数据层统一拦截一次，
            // 各调用点不必自己判 401。
            val clientFactory: (String, () -> Unit) -> ApiClient = remember(transport) {
                { address, onUnauthorized ->
                    ApiClient(address, transport, onUnauthorized = onUnauthorized)
                }
            }

            LumigatherTheme(mode = ThemeMode.Light) {
                AppRoot(
                    clientFactory = clientFactory,
                    addressStore = addressStore,
                    imageDownloader = imageDownloader,
                    authTokenStore = authTokenStore,
                )
            }
        }
    }
}

// 侧栏滑入/滑出时长。220ms 属"能感知到滑动、又不拖沓"的区间（动画惯例 200~250ms）。
private const val SIDEBAR_ANIMATION_MS = 220f

// 松手吸附的分界：拖过栏宽的 1/3 就全开，否则回弹。
// 取 1/3 而不是 1/2：手指常常只拖一小段就想开，门限太高会让「拖了却没反应」的挫败感更强。
private const val SIDEBAR_COMMIT_FRACTION = 3f

// 帧时间戳是纳秒，进度按毫秒算，这里做一次单位换算。
private const val NANOS_PER_MILLI = 1_000_000f

/**
 * 自己实现的 0..1 进度动画（缓出）。
 *
 * 为什么不用 animateFloatAsState：Kuikly 的动画 API 在 `com.tencent.kuikly.compose.animation.core`
 * 包里，但本工程的 commonMain 编译期看不到该包（运行时构件里有、编译期元数据里没有），
 * 直接引用会编不过。用 `withFrameNanos` 逐帧插值只需要 compose runtime，行为等价：
 * 每帧写一次状态，读这个状态的组合会跟着重画。
 *
 * 只服务于侧栏位移：它是一个"从当前值走到目标值、到点即停"的一次性动画，
 * 不需要弹簧、不需要中途打断重定向。
 */
@Composable
private fun animatedProgress(open: Boolean, dragProgress: Float?): Float {
    val progress = remember { mutableStateOf(if (open) 1f else 0f) }

    LaunchedEffect(open, dragProgress) {
        // 拖动中：把手指当前位置同步进来，然后立刻返回，不跑吸附动画 ——
        // 这一段是「跟手」的关键，侧栏位置完全由手指决定。
        if (dragProgress != null) {
            progress.value = dragProgress
            return@LaunchedEffect
        }

        // 松手（dragProgress 变回 null）：从「手指松开时的位置」继续走到目标。
        // 因为上面已经把这个位置写进 progress，这里的 from 就是离开点，不会跳变。
        val from = progress.value
        val to = if (open) 1f else 0f
        if (from == to) return@LaunchedEffect

        var startedAtNanos = -1L
        var finished = false
        // 逐帧推进直到走满；LaunchedEffect 的 key 变化（用户又把侧栏关掉）会取消这次循环并重开，
        // 于是"滑到一半反向"也能平滑地从中途往回走。
        while (!finished) {
            withFrameNanos { frameTimeNanos ->
                if (startedAtNanos < 0L) startedAtNanos = frameTimeNanos
                val elapsedMs = (frameTimeNanos - startedAtNanos) / NANOS_PER_MILLI
                val fraction = (elapsedMs / SIDEBAR_ANIMATION_MS).coerceIn(0f, 1f)
                progress.value = from + (to - from) * easeOutCubic(fraction)
                finished = fraction >= 1f
            }
        }
    }

    return progress.value
}

// 缓出曲线：开头快、结尾慢，滑入时才有"被稳稳停住"的观感。
private fun easeOutCubic(progress: Float): Float {
    val remaining = 1f - progress
    return 1f - remaining * remaining * remaining
}

/**
 * 侧栏跟手拖动手势（挂在网格区，不挂整屏）。
 *
 * 为什么不挂整屏：Kuikly 的 detectHorizontalDragGestures 内部用
 * awaitFirstDown(requireUnconsumed = false) 并在 slop 通过后 consume() 事件，
 * 父层挂载会连子组件（顶部 chip 条 LazyRow）的横向滚动一起吃掉。
 * 下沉到网格区后，顶栏/chip 条/底部状态栏的横向交互都不受影响。
 *
 * 松手吸附的分界是栏宽的 1/3：拖过就全开，否则回弹。
 */
private fun Modifier.sidebarDragGesture(
    enabled: Boolean,
    onLeftProgress: (Float?) -> Unit,
    onRightProgress: (Float?) -> Unit,
    onCommitLeft: () -> Unit,
    onCommitRight: () -> Unit,
    onDragFinished: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    // 侧栏已经开着时不接管：那时横向拖动应当交给侧栏内部（列表滚动等）。
    if (!enabled) return@pointerInput

    val sidebarWidthPx = HomeLayout.sidebarWidth.toPx()
    // 0=还没决定方向 1=拖左栏 2=拖右栏。方向在第一次移动时定下，全程不变。
    var draggingSide = 0
    var accumulated = 0f

    detectHorizontalDragGestures(
        onDragStart = {
            draggingSide = 0
            accumulated = 0f
        },
        onDragEnd = {
            val commitDistance = sidebarWidthPx / SIDEBAR_COMMIT_FRACTION
            when {
                draggingSide == 1 && accumulated > commitDistance -> onCommitLeft()
                draggingSide == 2 && accumulated < -commitDistance -> onCommitRight()
                draggingSide != 0 -> onDragFinished()
                else -> Unit
            }
            onDragFinished()
            draggingSide = 0
        },
        onDragCancel = {
            onDragFinished()
            draggingSide = 0
        },
        onHorizontalDrag = { _, dragAmount ->
            accumulated += dragAmount
            if (draggingSide == 0) {
                draggingSide = if (dragAmount > 0f) 1 else if (dragAmount < 0f) 2 else 0
            }
            // 进度 = 已拖距离 / 栏宽，钳在 0..1 —— 这就是「跟手」。
            if (draggingSide == 1) {
                onLeftProgress((accumulated / sidebarWidthPx).coerceIn(0f, 1f))
            } else if (draggingSide == 2) {
                onRightProgress((-accumulated / sidebarWidthPx).coerceIn(0f, 1f))
            }
        },
    )
}
