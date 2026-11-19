package ui.components

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import theme.AppTheme
import theme.LumigatherTheme
import theme.ThemeMode

/**
 * 组件总览屏：把每个原子组件按「默认 / 选中 / 禁用 / 加载 / 错误」排开，用来人工核对。
 *
 * 它不参与业务，只给开发和验收看：组件的样式问题（圆角不一致、禁用态看不清、
 * 三态占位没有重试入口）在代码里读不出来，必须排在一起看才暴露。
 */
@Composable
fun ComponentsShowcase(
    modifier: Modifier = Modifier,
    mode: ThemeMode = ThemeMode.Light,
) {
    LumigatherTheme(mode = mode) {
        val colors = AppTheme.colors
        val metrics = AppTheme.metrics

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(metrics.spacingMd),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
        ) {
            ShowcaseSection(title = "圆形图标按钮") {
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
                    AppIconButton(onClick = {}) { Text(text = "☰", color = colors.textPrimary, fontSize = metrics.fontBody) }
                    AppIconButton(onClick = {}) { Text(text = "👤", color = colors.textPrimary, fontSize = metrics.fontBody) }
                    AppIconButton(onClick = {}, enabled = false) {
                        Text(text = "✎", color = colors.textGhost, fontSize = metrics.fontBody)
                    }
                }
            }

            ShowcaseSection(title = "胶囊按钮（主 / 次 / 文字 / 禁用）") {
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
                    AppPillButton(text = "下载", onClick = {}, size = PillSize.Compact)
                    AppPillButton(text = "分享", onClick = {}, variant = PillVariant.Secondary, size = PillSize.Compact)
                    AppPillButton(text = "取消", onClick = {}, variant = PillVariant.Ghost, size = PillSize.Compact)
                    AppPillButton(text = "不可用", onClick = {}, enabled = false, size = PillSize.Compact)
                }
                Spacer(modifier = Modifier.height(metrics.spacingSm))
                AppPillButton(text = "首屏主操作", onClick = {}, size = PillSize.Large, modifier = Modifier.fillMaxWidth())
            }

            ShowcaseSection(title = "筛选胶囊（含分类色点）") {
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
                    AppChip(label = "全部", selected = true, onClick = {})
                    AppChip(label = "崩坏3", dotColor = colors.primary, onClick = {})
                    AppChip(label = "原神", dotColor = colors.textGhost, onClick = {})
                    AppChip(label = "禁用", enabled = false, onClick = {})
                }
            }

            ShowcaseSection(title = "卡片") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "卡片标题", color = colors.textPrimary, fontSize = metrics.fontTitle)
                    Spacer(modifier = Modifier.height(metrics.spacingXs))
                    Text(text = "卡片正文，用来验证圆角、投影与内边距。", color = colors.textSecondary, fontSize = metrics.fontSubtitle)
                }
            }

            ShowcaseSection(title = "列表行（默认 / 带副标题与值 / 选中 / 禁用）") {
                Column {
                    AppListRow(title = "壁纸图库", onClick = {})
                    AppListRow(
                        title = "明日方舟资源",
                        subtitle = "子目录 3 个",
                        value = "7",
                        onClick = {},
                    )
                    AppListRow(title = "游戏素材", selected = true, value = "128", onClick = {})
                    AppListRow(title = "表情包", enabled = false, onClick = {})
                }
            }

            ShowcaseSection(title = "分隔线（含缩进）") {
                AppDivider()
                Spacer(modifier = Modifier.height(metrics.spacingXs))
                AppDivider(indent = metrics.spacingMd)
            }

            ShowcaseSection(title = "三态占位：加载 / 空 / 错误（带重试）") {
                AppLoading()
                AppEmpty(hint = "换个分类试试")
                AppError(hint = "无法连接服务器 example.com:8080", onRetry = {})
            }
        }
    }
}

/** 分组小标题 + 内容，让总览屏的各段看起来像一节一节，而不是一坨组件堆在一起。 */
@Composable
private fun ShowcaseSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.textFaint,
            fontSize = metrics.fontMicro,
            modifier = Modifier.padding(bottom = metrics.spacingXs),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingXs),
            horizontalAlignment = Alignment.Start,
        ) {
            content()
        }
    }
}
