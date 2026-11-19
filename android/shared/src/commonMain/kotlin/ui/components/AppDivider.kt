package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.HorizontalDivider
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import theme.AppTheme

/**
 * 分隔线：设计规范主张「靠色块与间距分层，不用分割线」，
 * 所以这个组件是例外出口——只在「两组同类内容必须划清界限」时才用，别当默认装饰。
 *
 * @param indent 左侧缩进，让分隔线从文字起始处开始（图标下方留空），视觉上更整齐
 */
@Composable
fun AppDivider(
    modifier: Modifier = Modifier,
    indent: Dp = 0.dp,
) {
    val metrics = AppTheme.metrics
    HorizontalDivider(
        modifier = modifier.padding(start = indent),
        thickness = metrics.thicknessRule,
        color = AppTheme.colors.rule,
    )
}
