package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import theme.AppTheme

/**
 * 胶囊按钮：页面里所有「有文字的主动作」，主操作与次操作靠 [PillVariant] 区分。
 *
 * 用枚举而不是让调用方传颜色：这样「主按钮长什么样」只在组件里定义一次，
 * 换主色或调对比度时不需要去每个页面改调用点。
 */
enum class PillVariant {
    /** 实心主色：页面上最主要的那个动作 */
    Primary,

    /** 容器底 + 描边：并列的次要动作 */
    Secondary,

    /** 无底无边：视觉最弱，仅文字，用于「取消」这类动作 */
    Ghost,
}

/** 胶囊高度三档：列表内用紧凑、常规页面用常规、首屏主按钮用大。 */
enum class PillSize { Compact, Regular, Large }

@Composable
fun AppPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PillVariant = PillVariant.Primary,
    size: PillSize = PillSize.Regular,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusPill)

    val height = when (size) {
        PillSize.Compact -> metrics.heightPillCompact
        PillSize.Regular -> metrics.heightPillRegular
        PillSize.Large -> metrics.heightPillLarge
    }
    val horizontalPadding = when (size) {
        PillSize.Compact -> metrics.spacingMd
        PillSize.Regular -> metrics.spacingLg
        PillSize.Large -> metrics.spacingLg
    }
    val font = when (size) {
        PillSize.Compact -> metrics.fontSubtitle
        PillSize.Regular -> metrics.fontBody
        PillSize.Large -> metrics.fontTitle
    }

    // 禁用态统一压到 container + ghost：三种形态在禁用时长得一样，
    // 避免「禁用的主按钮」还留着主色底让人误以为可以点
    val background: Color = when {
        !enabled -> colors.container
        variant == PillVariant.Primary -> colors.primary
        variant == PillVariant.Secondary -> colors.container
        else -> Color.Transparent
    }
    val contentColor: Color = when {
        !enabled -> colors.textGhost
        variant == PillVariant.Primary -> colors.onPrimary
        else -> colors.textPrimary
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(background)
            // 只有次要形态有描边：主形态实心不需要，Ghost 形态故意不加框
            .then(
                if (variant == PillVariant.Secondary && enabled) {
                    Modifier.border(metrics.thicknessRule, colors.rule, shape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = font,
            fontWeight = if (variant == PillVariant.Primary) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
