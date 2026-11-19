// 右侧详情栏：大图预览 + 元信息区 + 底部操作。
//
// 数据只有一个 [ImageItem]，由页面在选择图片时塞进状态；这里不发请求、不做分页，
// 也不管自己是"从右边滑进来"的（位移在 AppRoot）。元信息的三列统计与信息行
// 直接复用 T5 的 DetailInfoBlock —— 那两个推导（大小是后端给的字符串、格式从文件名推）
// 已经在块内部，页面不该再写一遍。

package ui.screens.sidebar

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.ImageItem
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import theme.AppTheme
import ui.blocks.DetailInfoBlock
import ui.components.AppEmpty
import ui.components.AppIconButton
import ui.components.AppPillButton
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.HomeUiState

@Composable
fun RightSidebar(
    state: HomeUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val image = state.selectedImage

    // 与左侧栏镜像：面板从右侧滑入，所以只有左侧两角圆、右侧贴边。
    val shape = RoundedCornerShape(
        topStart = metrics.radiusLg,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
        bottomStart = metrics.radiusLg,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.surface)
            .padding(horizontal = metrics.spacingSm, vertical = metrics.spacingMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
    ) {
        SidebarHeader(onClose = onClose)

        if (image == null) {
            AppEmpty(
                modifier = Modifier.fillMaxSize(),
                title = "还没有选中图片",
                hint = "点网格里的任意一张看看",
            )
            return@Column
        }

        // 用 LazyColumn 而不是 Column：详情在小屏上会超出可视区，需要能滚。
        // 这个版本的 Kuikly 没有 Modifier.verticalScroll，能滚的只有 Lazy 系。
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = metrics.spacingXs),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
        ) {
            item {
                PreviewImage(image = image)
            }
            item {
                DetailInfoBlock(
                    image = image,
                    categoryName = state.selectedCategoryView?.label.orEmpty(),
                )
            }
            item {
                FileNameLine(name = image.name)
            }
        }

        BottomActionBar(onClose = onClose)
    }
}

@Composable
private fun SidebarHeader(onClose: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "详情",
            color = colors.textPrimary,
            fontSize = metrics.fontTitle,
            modifier = Modifier.weight(1f).padding(start = metrics.spacingXs),
        )
        AppIconButton(onClick = onClose) {
            Text(text = HomeLayout.iconClose, color = colors.textPrimary, fontSize = metrics.fontSubtitle)
        }
    }
}

/**
 * 大图预览：等比完整显示（Fit 而不是 Crop）。
 *
 * 详情页的目的是"看清楚这张图"，裁切会让人误判构图；缩略图才用 Crop。
 * 固定高度而不是撑满剩余空间：下面还有元信息，图无限长会把信息挤没。
 */
@Composable
private fun PreviewImage(image: ImageItem) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Image(
        painter = rememberAsyncImagePainter(model = image.absoluteUrl),
        contentDescription = image.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeLayout.previewHeight)
            .clip(RoundedCornerShape(metrics.radiusMd))
            .background(colors.container),
    )
}

// 文件名单独一行：网格里靠缩略图认图，详情里需要知道到底是哪个文件。
@Composable
private fun FileNameLine(name: String) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = name,
        color = colors.textSecondary,
        fontSize = metrics.fontCaption,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.spacingXs),
    )
}

// 底部操作条：目前只有"关闭"是真实可用的动作。
// 下载/分享还没有后端契约（下载要落手机相册、分享要经系统面板），故不摆假按钮——
// 详情区的「操作」页签会明确显示"没有可用操作"，比点不动更有用。
@Composable
private fun BottomActionBar(onClose: () -> Unit) {
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        AppPillButton(
            text = "关闭",
            onClick = onClose,
            modifier = Modifier.weight(1f),
            variant = PillVariant.Secondary,
        )
    }
}
