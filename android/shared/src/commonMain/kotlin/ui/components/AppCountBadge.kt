// 数量胶囊：分类行、收藏/游客入口共用的「固定宽度数字底块」。
//
// 为什么必须是固定宽度：这些胶囊在各行右侧竖直排列，宽度若随内容变化，
// 数字位数不同的行就会参差不齐；固定宽度 + 数字居中，一眼扫过去才是整齐一列。
//
// 为什么单独抽成组件：此前 blocks 与 screens 各有一份实现，且**细节已经漂移**——
// 一份用千分位、另一份直接 toString()，同一屏里出现「1,234」和「1234」两种写法。
// 视觉相同的东西只能有一份实现，否则下次改样式必然漏一处。

package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import theme.AppTheme

/**
 * 数量胶囊。
 *
 * @param text 已格式化好的文案（调用方负责千分位；本组件不做格式化，避免一处显示整数、
 *             另一处显示别的形态时，还要给组件加开关）
 * @param highlighted 选中行整块换成主色 —— 这是该行唯一的选中信号
 */
@Composable
fun AppCountBadge(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Box(
        modifier = modifier
            .width(metrics.widthCountBadge)
            .height(metrics.heightCountBadge)
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(if (highlighted) colors.primary else colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (highlighted) colors.onPrimary else colors.textSecondary,
            fontSize = metrics.fontCaption,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 千分位格式化：`1262` → `1,262`。
 *
 * 为什么不用现成库：只为一个函数引依赖不划算，实现本身也就一次扫描。
 * 边界：负数把符号放在分组之前；`Int.MIN_VALUE` 取负会溢出回自身，
 * 故先转 Long 再取绝对值。
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
