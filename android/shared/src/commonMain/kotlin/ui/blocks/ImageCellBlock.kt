package ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.combinedClickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.layout.ContentScale
import theme.AppTheme

/** 图片格子的三种状态。三态都必须有视觉出口，缺一个就会在弱网/坏图上露白块。 */
enum class ImageCellState { Loading, Ready, Failed }

/**
 * 图片格子：网格里的一格 —— 1:1 正方形、圆角、图片铺满裁切，并自带三态兜底。
 *
 * 为什么把三态收进格子里：网格动辄几十上百格，只要有一张图坏掉就让整屏空白或抛异常，
 * 用户看到的是「整个页面坏了」。所以单格的加载/失败必须就地消化：失败只影响这一格，
 * 画一块「无法加载」占位，其余格照常显示。
 *
 * 本块不发任何业务请求：它只把 [imageUrl] 交给平台图片加载器（Kuikly 的图片适配器），
 * 列表数据、分页、错误提示都由页面层负责。
 *
 * @param name 图片名，用作无障碍描述（网格里没有文字时，读屏只能靠它）
 * @param sizeText 尺寸文案（如 `2.4 MB`），非空时在右下角显示一个小角标
 * @param stateOverride 仅供预览/自检注入状态用；正常运行应留空，让真实加载结果决定
 * @param onClick 点击回调，块内部不做任何跳转
 */
@Composable
fun ImageCellBlock(
    imageUrl: String,
    name: String,
    modifier: Modifier = Modifier,
    sizeText: String = "",
    stateOverride: ImageCellState? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    // 激活态（放大中的那张）：发亮描边。颜色取主题 primary，不新造色值
    highlighted: Boolean = false,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusSm)

    // 激活视觉的唯一来源：0f = 常态，1f = 放大态。
    // 现在只有 0/1 两种取值；将来的过渡动画就是把这一行换成一个连续进度，
    // 描边（宽度 + 透明度）全部从它派生 —— 这是本轮刻意留出的干净插入点。
    val highlightAmount = if (highlighted) 1f else 0f

    // 换图（url 变化）就重置为加载中，否则会短暂保留上一张的失败态
    var loadedPhase by remember(imageUrl) { mutableStateOf(ImageCellState.Loading) }
    val painter = rememberAsyncImagePainter(
        model = imageUrl,
        onLoading = { loadedPhase = ImageCellState.Loading },
        onSuccess = { loadedPhase = ImageCellState.Ready },
        onError = { loadedPhase = ImageCellState.Failed },
    )
    val phase = stateOverride ?: loadedPhase

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.container)
            // 激活描边：**无条件挂上**，只让宽度/透明度随 highlightAmount 变。
            //
            // 不写成 `if (highlighted) Modifier.border(...) else Modifier` 的原因：
            // Kuikly 下把 border 条件性挂载/移除时，收起那一刻的绘制层不会跟着重画，
            // 会留下"回 1×1 了但描边还在"的残影。改成"结构不变、只变参数"就没有这个刷新问题。
            //
            // ↓↓↓ 动画接入点 ↓↓↓
            // 将来要做过渡动画时，**只改 highlightAmount 的来源**（把下面这行的 0f/1f
            // 换成 animateFloatAsState 之类驱动的 0f..1f 进度），描边宽度与透明度都从它派生，
            // 调用方与 Modifier 链都不用动。
            .border(
                width = metrics.thicknessRule * HIGHLIGHT_BORDER_SCALE * highlightAmount,
                color = colors.primary.copy(alpha = highlightAmount),
                shape = shape,
            )
            .then(gestureModifier(onClick = onClick, onLongClick = onLongClick))
    ) {
        // Image 必须无条件挂在树里：Kuikly 的 AsyncImagePainter 靠「被 Image 绘制」才发起加载。
        // 若按状态条件渲染（只有 Ready 才挂 Image），会形成死锁 —— 不挂 Image 就不加载，
        // 不加载就永远等不到 onSuccess，状态永远停在 Loading，格子一直显示底色。
        // 因此这里始终渲染图片，未就绪时用底色遮住，失败时再盖出错误文案。
        Image(
            painter = painter,
            contentDescription = name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        when (phase) {
            // 加载中：用底色盖住尚未解码完成的画布，避免半张图闪一下
            ImageCellState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.container),
            )

            ImageCellState.Failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.container),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "无法加载", color = colors.textGhost, fontSize = metrics.fontMicro)
            }

            ImageCellState.Ready -> Unit
        }

        if (sizeText.isNotBlank() && phase == ImageCellState.Ready) {
            Text(
                text = sizeText,
                color = colors.textSecondary,
                fontSize = metrics.fontMicro,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(metrics.spacingXs)
                    .clip(shape)
                    .background(colors.surface)
                    .padding(horizontal = metrics.spacingXs),
            )
        }
    }
}

/**
 * 点按 / 长按的组合手势。
 *
 * 为什么走 `combinedClickable`：Kuikly 的 `Clickable.kt` 里只有它把 `onLongClick` 接到了
 * `detectTapGestures(onLongPress = ...)`；`Modifier.clickable` 没有长按参数，
 * 而 `pointerInput { detectTapGestures(...) }` 虽然底层存在，直接用会丢掉 clickable 的
 * indication/语义（无障碍节点上的"长按"动作）。
 *
 * 只在确实需要长按时才升到 combinedClickable：它带 `@ExperimentalFoundationApi`，
 * 普通点击继续走稳定的 `clickable`，把实验面收窄到真正用到的那类卡片。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun gestureModifier(onClick: (() -> Unit)?, onLongClick: (() -> Unit)?): Modifier = when {
    onLongClick == null && onClick == null -> Modifier
    onLongClick == null -> Modifier.clickable(onClick = onClick ?: {})
    else -> Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
}

// 激活描边宽度是常规分隔线的 2 倍：1px 在浅色底上几乎看不出"这张被选中了"
private const val HIGHLIGHT_BORDER_SCALE = 2
