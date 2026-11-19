package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import theme.AppTheme

/**
 * 选中态「淡主色底」的浓度：够看出选中，又不至于让上面的文字发灰。
 * theme 里没有「淡洗底色」这种语义槽，且只有列表选中一种用途，因此放在本文件；
 * 将来若有第二处需要同款淡底，应回 theme 补一个 surfaceSelected 语义色，而不是复制这个常量。
 */
private const val SELECTION_WASH_ALPHA = 0.12f

/**
 * 列表行：分类列表、目录列表、设置项都是「左图标 + 标题 + 右侧值/更多」这一种形态。
 *
 * 选中态刻意做成「淡主色圆角底 + 右侧圆点」两重信号：只靠底色的话，深色屏或低亮度下
 * 容易看不出选中；只靠圆点的话，又不够一眼定位当前项。
 *
 * @param selected 是否当前选中项（左栏分类列表用它表达「正在看这一类的图」）
 * @param leading 左侧图标插槽，由调用方给图标内容；不给则整行不留图标位
 * @param value 右侧数值/状态文字
 * @param trailing 右侧尾部插槽（箭头、更多按钮、开关等），优先级高于 [value]
 */
@Composable
fun AppListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusMd)

    val background = if (selected) colors.primary.copy(alpha = SELECTION_WASH_ALPHA) else colors.surface
    // 选中与否都用主文字色：选中态已经靠淡底 + 圆点表达，不再额外改字色，
    // 避免「选中项文字更黑」这种双重强调在暗色主题下反而更糊
    val titleColor = if (enabled) colors.textPrimary else colors.textGhost

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick))
            .padding(horizontal = metrics.spacingSm, vertical = metrics.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(metrics.spacingSm))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = titleColor, fontSize = metrics.fontBody)
            if (subtitle != null) {
                Text(text = subtitle, color = colors.textFaint, fontSize = metrics.fontCaption)
            }
        }

        when {
            trailing != null -> trailing()
            value != null -> Text(text = value, color = colors.textSecondary, fontSize = metrics.fontSubtitle)
        }

        if (selected) {
            Spacer(modifier = Modifier.width(metrics.spacingSm))
            Box(
                modifier = Modifier
                    .size(metrics.sizeDot)
                    .clip(shape)
                    .background(colors.primary),
            )
        }
    }
}
