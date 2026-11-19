// 三栏骨架的根：主界面常驻，两侧栏从边缘滑出，中间那层遮罩负责"点一下收起"。
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.LumigatherRepository
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
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.launch
import theme.AppTheme
import theme.LumigatherTheme
import theme.ThemeMode
import ui.screens.main.HomeScreen
import ui.screens.sidebar.LeftSidebar
import ui.screens.sidebar.RightSidebar
import ui.screens.state.HomeStateHolder

// 页面名：:app 侧打开页面时用它（也是 KSP 生成的页面注册表里的键）。
// 公开而不是私有：宿主那边要引用同一个常量，两处各写一份字符串迟早会漂移，
// 而漂移的后果是"装上去只看到空白页"，很难一眼看出是拼写问题。
const val HOME_PAGE_NAME = "LumigatherHome"

// 宿主通过 pageData 传进来的服务端地址键名。地址不在 UI 层写死：
// 换机器/换端口只改宿主一处，页面代码不动。
private const val PAGE_DATA_BASE_URL = "baseUrl"

/**
 * 传输层工厂的平台声明。
 *
 * commonMain 看不到 java.* 与 Android API，所以具体用哪个 HTTP 库只能由平台侧给。
 * 声明成 expect 而不是在页面里 `if (平台) ...`，好处是"缺实现"会变成编译错误而不是运行时空白。
 */
expect fun createDefaultHttpTransport(): HttpTransport

/**
 * 根组合：拿到状态持有者，把它翻译成三层版面。
 *
 * @param repository 数据入口，由页面入口注入（预览/自检时可以塞假实现）
 */
@Composable
fun AppRoot(
    repository: LumigatherRepository,
    modifier: Modifier = Modifier,
    stateHolder: HomeStateHolder = remember(repository) { HomeStateHolder(repository) },
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()

    // 读 state 的字段即订阅：任何 mutate 都会让下面重画
    val state = stateHolder.state

    // 启动链路只跑一次：拉分类 → 选中第一个 → 拉第一页 → 后台补张数
    LaunchedEffect(stateHolder) { stateHolder.bootstrap() }

    // 两个侧栏各自的动画进度。左右用同一条曲线与时长，手感才一致。
    val leftProgress = animatedProgress(state.leftSidebarOpen)
    val rightProgress = animatedProgress(state.rightSidebarOpen)
    // 任一侧栏打开都要把主界面推开并压暗，所以取两者的较大值作为"整体打开程度"。
    val openProgress = if (leftProgress > rightProgress) leftProgress else rightProgress

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // 第一层：主界面。位移方向由"哪一侧打开"决定：左栏开则右移，右栏开则左移。
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                onOpenLeftSidebar = stateHolder::openLeftSidebar,
                onSelectCategory = { categoryName -> scope.launch { stateHolder.selectCategory(categoryName) } },
                onOpenImage = stateHolder::openDetail,
                onLoadMore = { scope.launch { stateHolder.loadMore() } },
                onRetry = { scope.launch { stateHolder.retry() } },
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

        // 第三层左：分类栏。从屏幕外(chin)滑进来：进度 0 时整体左移一个栏宽。
        if (leftProgress > 0f) {
            LeftSidebar(
                state = state,
                onSelectCategory = { categoryName -> scope.launch { stateHolder.selectCategory(categoryName) } },
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
                onClose = stateHolder::closeSidebars,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(HomeLayout.sidebarWidth)
                    .fillMaxHeight()
                    .offset(x = HomeLayout.sidebarWidth * (1f - rightProgress)),
            )
        }

        // 左边缘手势热区：从最左侧往右拖即开分类栏。
        // 只在两侧栏都收着时挂上去，否则会跟侧栏内部的点击/输入抢事件。
        if (!state.leftSidebarOpen && !state.rightSidebarOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(HomeLayout.edgeSwipeWidth)
                    .fillMaxHeight()
                    // 手势的 key 用空 Unit：这个热区没有需要随状态重建的输入，重建反而会丢手势
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            // 只认向右的位移：向左拖是"关"，但此时侧栏本来就是关的，不用处理
                            if (dragAmount > 0f) stateHolder.openLeftSidebar()
                        }
                    },
            )
        }
    }
}

/**
 * Kuikly 页面入口。
 *
 * 三件事：从 pageData 取服务端地址 → 建数据层 → 把主题与根组合挂上去。
 * 这里不写任何布局：布局全在 [AppRoot] 及其子页面里，页面类只做装配。
 */
@Page(HOME_PAGE_NAME)
class LumigatherHomePage : ComposeContainer() {

    override fun willInit() {
        super.willInit()

        setContent {
            // 地址在组合期读：pageData 要等页面 attach 之后才有值，willInit 阶段可能还是空的
            val baseUrl = remember { resolveBaseUrl() }
            val repository = remember(baseUrl) {
                LumigatherRepository(ApiClient(baseUrl, createDefaultHttpTransport()))
            }

            LumigatherTheme(mode = ThemeMode.Light) {
                AppRoot(repository = repository)
            }
        }
    }

    // 读宿主传进来的服务端地址。读不到就给空串，让数据层报"地址无效"，
    // 由页面正常显示错误态与重试入口 —— 比在这里崩掉或猜一个默认地址都好。
    private fun resolveBaseUrl(): String = runCatching {
        pageData.params.optString(PAGE_DATA_BASE_URL, "")
    }.getOrDefault("")
}

// 侧栏滑入/滑出时长。220ms 属"能感知到滑动、又不拖沓"的区间（动画惯例 200~250ms）。
private const val SIDEBAR_ANIMATION_MS = 220f

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
private fun animatedProgress(open: Boolean): Float {
    val progress = remember { mutableStateOf(if (open) 1f else 0f) }

    LaunchedEffect(open) {
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
