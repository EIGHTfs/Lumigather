package ui.blocks

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.CountState
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import theme.AppTheme
import ui.components.AppCountBadge
import ui.components.formatCountThousands
import ui.components.AppListRow

/**
 * 分类项：左侧分类栏的每一行 —— 一个小圆点色标 + 分类名 + 右侧张数胶囊，选中时加圆点。
 *
 * 为什么单独成块而不是页面里写：分类项在左侧栏、底部分类条、搜索结果三处都会用到，
 * 而「色标多大、张数怎么显示、选中怎么标」这三件事必须全场一致。
 *
 * 纯展示：网络与统计由页面/仓库负责，这里只把 [countState] 翻译成一个胶囊；
 * 状态未知时干脆不显示胶囊，避免出现「0」这种误导（未知 ≠ 没有）。
 */
@Composable
fun CategoryBlock(
    name: String,
    color: String,
    modifier: Modifier = Modifier,
    label: String = name,
    imageCount: Int = 0,
    selected: Boolean = false,
    countState: CountState = CountState.UNKNOWN,
    onClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    AppListRow(
        title = label.ifBlank { name },
        modifier = modifier,
        selected = selected,
        // 选中信号交给尾部胶囊变主色，不再额外挂圆点（避免同一行两处重复强调）
        showSelectionDot = false,
        onClick = onClick,
        // 色标用 6dp 小圆点而不是色块：分类名才是信息主体，色块会把视线抢走
        leading = {
            Box(
                modifier = Modifier
                    .size(metrics.sizeDot)
                    .clip(RoundedCornerShape(metrics.radiusPill))
                    .background(colorFromHex(color, colors.primary)),
            )
        },
        // 张数走尾部插槽（而不是 value 参数的裸文字）：需要固定宽度的胶囊才能右侧对齐
        trailing = {
            // 文案由 badgeText 决定（未知状态返回 null，整行不画胶囊），胶囊本身是共用组件
            badgeText(countState, imageCount)?.let { text ->
                AppCountBadge(text = text, highlighted = selected)
            }
        },
    )
}

/**
 * 把统计状态翻译成胶囊里的内容。
 *
 * 刻意区分「还没统计」与「统计失败」：前者安静等着，后者要让用户知道数字不准；
 * 两者都显示 0 会让人以为这个分类是空的。未知状态返回 null，整行不画胶囊。
 */
private fun badgeText(countState: CountState, imageCount: Int): String? = when (countState) {
    CountState.READY -> formatCountThousands(imageCount)
    CountState.LOADING -> BADGE_PLACEHOLDER_LOADING
    CountState.FAILED -> BADGE_PLACEHOLDER_FAILED
    CountState.UNKNOWN -> null
}


private const val NEGATIVE_SIGN = "-"

/** 统计中：用省略号占位，保持胶囊宽度不变，列不会错位 */
private const val BADGE_PLACEHOLDER_LOADING = "…"

/** 统计失败：用短横占位，与「还没有数字」区分 */
private const val BADGE_PLACEHOLDER_FAILED = "—"
