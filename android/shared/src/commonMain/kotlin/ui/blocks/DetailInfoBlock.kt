package ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eightfs.lumigather.data.ImageItem
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.components.AppCard
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant

/** 详情区的顶部切换项。信息看元数据，操作看能对这个文件做什么。 */
enum class DetailTab { Info, Actions }

/**
 * 详情信息区：右侧栏下半部分 ——「三列统计 + 图标行式信息 + 底部操作」。
 *
 * 为什么独立成块：详情区在「点图看大图」和「搜索结果预览」两处都要用，
 * 而且它的字段映射坑很多（大小是后端格式化好的字符串、格式要从文件名推、修改时间只取日期），
 * 这些推导逻辑必须只有一处，否则两个入口显示的元信息会不一致。
 *
 * 纯展示：数据由 [image] 传入，操作只回调给页面（[onDownload] / [onShare]），
 * 块内部不请求任何接口、不缓存任何全局状态。这里记住的只是「当前停在哪个页签」这种界面状态。
 */
@Composable
fun DetailInfoBlock(
    image: ImageItem,
    modifier: Modifier = Modifier,
    categoryName: String = "",
    onDownload: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    var currentTab by remember(image.path) { mutableStateOf(DetailTab.Info) }

    AppCard(modifier = modifier) {
        // 分段控件：选中的用主色实心，未选中的退成纯文字，避免两个都像按钮
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs)) {
            AppPillButton(
                text = "信息",
                onClick = { currentTab = DetailTab.Info },
                variant = if (currentTab == DetailTab.Info) PillVariant.Primary else PillVariant.Ghost,
                size = PillSize.Compact,
            )
            AppPillButton(
                text = "操作",
                onClick = { currentTab = DetailTab.Actions },
                variant = if (currentTab == DetailTab.Actions) PillVariant.Primary else PillVariant.Ghost,
                size = PillSize.Compact,
            )
        }

        Spacer(modifier = Modifier.height(metrics.spacingMd))

        when (currentTab) {
            DetailTab.Info -> {
                StatsRow(sizeText = image.sizeText, formatText = image.extensionText, categoryName = categoryName)
                Spacer(modifier = Modifier.height(metrics.spacingMd))
                InfoLine(icon = "📁", label = "路径", value = image.path)
                InfoLine(icon = "🕒", label = "修改", value = image.modifiedDateText)
                InfoLine(icon = "📐", label = "格式", value = image.extensionText)
            }

            DetailTab.Actions -> ActionsSection(onDownload = onDownload, onShare = onShare)
        }
    }
}

/** 三列统计：数字用标题字号压住视觉，标签用极小字号退后。 */
@Composable
private fun StatsRow(sizeText: String, formatText: String, categoryName: String) {
    val metrics = AppTheme.metrics
    Row(modifier = Modifier.fillMaxWidth()) {
        StatCell(value = sizeText.ifBlank { EMPTY_VALUE }, label = "大小", modifier = Modifier.weight(1f))
        StatCell(value = formatText.ifBlank { EMPTY_VALUE }, label = "格式", modifier = Modifier.weight(1f))
        StatCell(value = categoryName.ifBlank { EMPTY_VALUE }, label = "分类", modifier = Modifier.weight(1f))
    }
}

/** 一格统计：上面是大字数值，下面是灰色标签。 */
@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    Column(modifier = modifier) {
        Text(text = value, color = colors.textPrimary, fontSize = metrics.fontTitle)
        Text(text = label, color = colors.textFaint, fontSize = metrics.fontCaption)
    }
}

/**
 * 「图标 + 标签 + 值」一行。
 *
 * 值是长路径时会被截断成省略号：详情区高度有限，路径全展开会把操作区挤出屏幕，
 * 而用户真想看完整路径时点一下图片就能复制/查看（页面层的交互）。
 */
@Composable
private fun InfoLine(icon: String, label: String, value: String) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (value.isBlank()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon, fontSize = metrics.fontSubtitle)
        Spacer(modifier = Modifier.width(metrics.spacingXs))
        Text(text = label, color = colors.textFaint, fontSize = metrics.fontSubtitle)
        Spacer(modifier = Modifier.width(metrics.spacingSm))
        Text(
            text = value,
            color = colors.textSecondary,
            fontSize = metrics.fontSubtitle,
            maxLines = INFO_LINE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(metrics.spacingXs))
}

/** 操作页签：没有回调时引导到下方动作条，而不是留一句"没有可用操作"让人以为功能坏了。 */
@Composable
private fun ActionsSection(onDownload: (() -> Unit)?, onShare: (() -> Unit)?) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (onDownload == null && onShare == null) {
        Text(
            text = "这张图的操作用下方动作条：下载可存到相册，收藏需要先登录",
            color = colors.textFaint,
            fontSize = metrics.fontCaption,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
        if (onDownload != null) {
            AppPillButton(text = "下载", onClick = onDownload, size = PillSize.Compact)
        }
        if (onShare != null) {
            AppPillButton(
                text = "分享",
                onClick = onShare,
                variant = PillVariant.Secondary,
                size = PillSize.Compact,
            )
        }
    }
}

/** 缺值占位：用一个短横而不是空字符串，让「没有这项」和「排版错位」区分开 */
private const val EMPTY_VALUE = "—"

private const val INFO_LINE_MAX_LINES = 2
