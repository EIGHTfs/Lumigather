package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.Color
import theme.AppTheme

/**
 * 筛选胶囊：横向一排「我只看这一类」的切换项，选中态靠实心主色表达。
 *
 * 与 [AppPillButton] 的区别是语义：胶囊按钮「触发动作」，chip「切换筛选」，
 * 所以 chip 默认带选中态、尺寸更紧凑，可以在同一条里连排十几个。
 *
 * @param dotColor 前置色点。它是**数据**不是样式——值来自服务端
 *   `/api/lumigather` 的 `categories[].color`（每个游戏分类一个专属色）。
 *   这是本组件唯一接收 Color 的入口，其余配色一律走主题语义色。
 */
@Composable
fun AppChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    dotColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusPill)

    val background = when {
        selected -> colors.primary
        else -> colors.container
    }
    val contentColor = when {
        !enabled -> colors.textGhost
        selected -> colors.onPrimary
        else -> colors.textSecondary
    }

    Row(
        modifier = modifier
            .height(metrics.heightPillCompact)
            .clip(shape)
            .background(background)
            .then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick))
            .padding(horizontal = metrics.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Spacer(
                modifier = Modifier
                    .size(metrics.sizeDot)
                    .clip(shape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(metrics.spacingXs))
        }
        Text(text = label, color = contentColor, fontSize = metrics.fontSubtitle)
    }
}

/**
 * 分类卡里的色点方标：分类列表要给「这个分类是什么颜色」一个稳定的视觉锚，
 * 但不需要文字，所以单独抽一个方点（圆角小一档，与圆形的选中点区分开）。
 */
@Composable
fun AppColorDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val metrics = AppTheme.metrics
    Box(
        modifier = modifier
            .size(metrics.heightPillCompact)
            .clip(RoundedCornerShape(metrics.radiusSm))
            .background(color),
    )
}
