package ui.blocks

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.CountState
import com.tencent.kuikly.compose.ui.Modifier
import theme.AppTheme
import ui.components.AppColorDot
import ui.components.AppListRow

/**
 * 分类项：左侧分类栏的每一行 —— 一个专属色方块 + 分类名 + 图片张数，选中时加圆点。
 *
 * 为什么单独成块而不是页面里写：分类项在左侧栏、底部分类条、搜索结果三处都会用到，
 * 而「色方块取哪个色、张数怎么显示、选中怎么标」这三件事必须全场一致。
 *
 * 纯展示：网络与统计由页面/仓库负责，这里只把 [countState] 翻译成一行文字；
 * 状态未知时干脆不显示张数，避免出现「0 张」这种误导（未知 ≠ 没有）。
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

    AppListRow(
        title = label.ifBlank { name },
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        leading = { AppColorDot(color = colorFromHex(color, colors.primary)) },
        value = countText(countState, imageCount),
    )
}

/**
 * 把统计状态翻译成右侧那一列短文案。
 *
 * 刻意区分「还没统计」与「统计失败」：前者该安静等着，后者要让用户知道数字可能不准，
 * 两者都显示成 0 会让人以为这个分类是空的。
 */
private fun countText(countState: CountState, imageCount: Int): String? = when (countState) {
    CountState.READY -> "$imageCount 张"
    CountState.LOADING -> "统计中"
    CountState.FAILED -> "统计失败"
    CountState.UNKNOWN -> null
}
