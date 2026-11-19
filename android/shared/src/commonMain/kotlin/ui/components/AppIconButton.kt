package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.LocalContentColor
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.draw.shadow
import theme.AppTheme

/**
 * 圆形图标按钮：顶栏左右两侧那类「只放一个图标、没有文字」的操作入口。
 *
 * 它把「圆形 + 投影 + 点击态 + 禁用态」固定下来，调用方只决定图标内容与点击行为，
 * 不会再出现「这个页面顶栏按钮 40、那个页面 44」的漂移。
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusPill)

    // 禁用态不靠调透明度：浅底上压透明会发灰脏，改用 container 底 + ghost 文字更干净
    val background = if (enabled) colors.surface else colors.container
    val tint = if (enabled) colors.textPrimary else colors.textGhost

    Box(
        modifier = modifier
            .size(metrics.sizeIconButton)
            .shadow(elevation = metrics.elevationResting, shape = shape, clip = false)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 让调用方传进来的图标自动继承当前配色（禁用态自动变 ghost），不用逐处传色
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}
