package ui.blocks

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.CountState
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import theme.AppTheme
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
        trailing = { CountBadge(countState = countState, imageCount = imageCount) },
    )
}

/**
 * 张数胶囊：固定宽高 + 数字居中，让整列的数值竖着看是一条直线。
 *
 * 为什么不用裸文字右对齐：数字位数不同（"806" 与 "72,651"）时右对齐会让这一列的
 * 左边界参差不齐，扫读时眼睛要不断重新定位；等宽胶囊把每行占位固定下来，
 * 左右边界都成为直线，代价只是短数字周围多一点留白。
 */
@Composable
private fun CountBadge(countState: CountState, imageCount: Int) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    val text = badgeText(countState, imageCount) ?: return
    // 数字用次要色、占位符用更弱的 ghost 色：一眼能分出「这是真数字」和「还在算/没算出来」
    val textColor = if (countState == CountState.READY) colors.textSecondary else colors.textGhost

    Box(
        modifier = Modifier
            .width(metrics.widthCountBadge)
            .height(metrics.heightCountBadge)
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = metrics.fontCaption,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
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

/**
 * 千分位格式化：`1262` → `1,262`，`0` → `0`。
 *
 * 自己写而不引库：整个 app 只有这一处需要分组分隔，为它加一个依赖不值当。
 * 边界处理：
 * - 0 与一位数不带分隔符；
 * - 负数保留符号再分组（当前业务不会出现，但传入负数不能格式化出 `-,1,262`）；
 * - `Int.MIN_VALUE` 取负会溢出回自己，所以先升到 Long 再取绝对值；
 * - 最大值 `2,147,483,647` 正常分组。
 */
internal fun formatCountThousands(value: Int): String {
    val isNegative = value < 0
    val digits = (if (isNegative) -value.toLong() else value.toLong()).toString()

    val grouped = StringBuilder(digits.length + digits.length / THOUSAND_GROUP_SIZE)
    for (index in digits.indices) {
        if (index > 0 && (digits.length - index) % THOUSAND_GROUP_SIZE == 0) {
            grouped.append(THOUSAND_SEPARATOR)
        }
        grouped.append(digits[index])
    }

    return if (isNegative) "$NEGATIVE_SIGN$grouped" else grouped.toString()
}

/** 每三位一组 */
private const val THOUSAND_GROUP_SIZE = 3

private const val THOUSAND_SEPARATOR = ","

private const val NEGATIVE_SIGN = "-"

/** 统计中：用省略号占位，保持胶囊宽度不变，列不会错位 */
private const val BADGE_PLACEHOLDER_LOADING = "…"

/** 统计失败：用短横占位，与「还没有数字」区分 */
private const val BADGE_PLACEHOLDER_FAILED = "—"
