package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.draw.shadow
import com.tencent.kuikly.compose.ui.unit.Dp
import theme.AppTheme

/**
 * 通用圆角卡片：整个界面靠「色块 + 圆角 + 投影」分层，不画边框，
 * 所有成组内容都该长在这块底上，所以统一成一个组件避免各页面自己拼。
 *
 * @param padding 内容内边距，默认取主题的中档间距
 * @param elevation 投影高度：静止卡片用 `metrics.elevationResting`，浮起面板用 `metrics.elevationRaised`
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = AppTheme.metrics.spacingMd,
    elevation: Dp = AppTheme.metrics.elevationResting,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusMd)

    Column(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(colors.surface)
            .padding(padding),
        content = content,
    )
}
