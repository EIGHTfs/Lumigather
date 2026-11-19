package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.CircularProgressIndicator
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import theme.AppTheme

/**
 * 三态占位：加载中 / 空 / 出错。
 *
 * 为什么必须共用一套：这三种状态在任何列表页都会出现，如果各页面自己写，
 * 就会出现「加载中居中、空状态偏上、错误文案字不一样」的杂乱感；
 * 更要紧的是错误态必须带重试入口，散写着很容易漏掉，让用户只能杀进程重进。
 */

/** 加载中：转圈 + 一行说明。默认文案不带标点，避免各页面标点风格不一致。 */
@Composable
fun AppLoading(
    modifier: Modifier = Modifier,
    message: String = "加载中",
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(
        modifier = modifier.padding(metrics.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = colors.primary)
        Spacer(modifier = Modifier.height(metrics.spacingSm))
        Text(text = message, color = colors.textSecondary, fontSize = metrics.fontSubtitle)
    }
}

/** 空状态：没有内容时的说明，可选给一句「怎么才会有内容」的提示。 */
@Composable
fun AppEmpty(
    modifier: Modifier = Modifier,
    title: String = "这里还没有内容",
    hint: String? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(
        modifier = modifier.padding(metrics.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, color = colors.textPrimary, fontSize = metrics.fontBody)
        if (hint != null) {
            Spacer(modifier = Modifier.height(metrics.spacingXs))
            Text(text = hint, color = colors.textFaint, fontSize = metrics.fontCaption)
        }
    }
}

/**
 * 错误状态：说明发生了什么 + 重试入口。
 *
 * 配色上没有「危险色」可用（设计规范色板只有冷调蓝灰 + 蓝紫主色，没有红系），
 * 所以错误态靠「主色重试按钮 + 次要色说明」表达可操作性，而不是靠红色吓人。
 *
 * @param onRetry 重试回调。为 null 时不画按钮（例如错误不可重试的场合）
 */
@Composable
fun AppError(
    modifier: Modifier = Modifier,
    title: String = "加载失败",
    hint: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(
        modifier = modifier.padding(metrics.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, color = colors.textPrimary, fontSize = metrics.fontBody)
        if (hint != null) {
            Spacer(modifier = Modifier.height(metrics.spacingXs))
            Text(text = hint, color = colors.textSecondary, fontSize = metrics.fontCaption)
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(metrics.spacingMd))
            AppPillButton(
                text = "重试",
                onClick = onRetry,
                variant = PillVariant.Primary,
                size = PillSize.Compact,
            )
        }
    }
}
