// 网格里的"非图片"卡片：文件夹，以及目录层级用的面包屑条。
//
// 为什么单独成文件：这些卡片是"网格条目"这个抽象的具体形态之一，和图片卡片并列；
// 放在 HomeScreen 里会让那个文件同时承担"布局编排"和"卡片外观"两件事。
//
// 压缩包不在这里：包内图片由服务端 /api/zip/img 逐张给出、直接当普通图片摊进网格，
// 既没有"压缩包卡片"也没有"打开"这一步（见 ArchiveController.loadArchives）。
//
// 两条视觉约定（与图片卡片对齐）：
//   - 都用 aspectRatio(1f)：与图片格子同尺寸，混排时行高一致，不会出现"文件夹矮一截"
//   - 张数未知（count < 0）显示「目录」而不是「0 张」—— 后端未统计不等于没有图

package ui.screens.main

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.components.AppCard
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant

/** 文件夹卡片。count < 0 表示后端未统计（不是空目录），显示「目录」。 */
@Composable
fun FolderCard(name: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    AppCard(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        padding = metrics.spacingSm,
    ) {
        Text(text = GLYPH_FOLDER, color = colors.textSecondary, fontSize = metrics.fontSubtitle)
        Spacer(Modifier.height(metrics.spacingXs))
        Text(
            text = name,
            color = colors.textPrimary,
            fontSize = metrics.fontCaption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (count >= 0) "$count 张" else UNKNOWN_COUNT_TEXT,
            color = colors.textFaint,
            fontSize = metrics.fontMicro,
        )
    }
}

/**
 * 面包屑条：任一段可点跳回（段号 = 层级深度，0 = 分类根）。
 *
 * 「‹ 上级」按钮的可用性由状态层的 canGoUp 决定（= 目录栈非空）：
 * `/api/dir` 不返回 canUp，栈深度是唯一可靠判据。
 */
@Composable
fun BreadcrumbBar(
    crumbs: List<String>,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onCrumbClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val lastIndex = crumbs.lastIndex

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = metrics.spacingLg, vertical = metrics.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        if (canGoUp) {
            AppPillButton(
                text = BACK_LABEL,
                onClick = onGoUp,
                variant = PillVariant.Ghost,
                size = PillSize.Compact,
            )
        }
        crumbs.forEachIndexed { index, label ->
            if (index > 0) {
                Text(text = CRUMB_SEPARATOR, color = colors.textFaint, fontSize = metrics.fontMicro)
            }
            Text(
                text = label,
                // 最后一段是"你现在在哪"，用正文色；前面的段是路径，弱化但仍可点
                color = if (index == lastIndex) colors.textPrimary else colors.textSecondary,
                fontSize = metrics.fontCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onCrumbClick(index) },
            )
        }
    }
}

// 字形图标：本项目不引图标库，与左栏/分类行保持同一种线性字形风格
private const val GLYPH_FOLDER = "📁"
private const val UNKNOWN_COUNT_TEXT = "目录"
private const val BACK_LABEL = "‹ 上级"
private const val CRUMB_SEPARATOR = "/"
