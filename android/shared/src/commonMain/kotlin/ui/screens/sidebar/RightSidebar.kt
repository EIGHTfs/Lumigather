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
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.DownloadUiState
import ui.screens.state.HomeUiState

@Composable
fun RightSidebar(
    state: HomeUiState,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
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
                    // 详情区的「操作」页签也接真实下载：两个入口共用页面层的同一个动作，
                    // 不各自实现一遍（否则一个存进相册、一个只是提示，用户会被搞糊涂）。
                    onDownload = onDownload,
                )
            }
            item {
                FileNameLine(name = image.name)
            }
        }

        ActionStatusLine(state = state)

        BottomActionBar(
            isDownloading = state.downloadState is DownloadUiState.Downloading,
            isFavorite = state.isCurrentImageFavorite,
            isFavoriteMutating = state.isFavoriteMutating,
            onDownload = onDownload,
            onFavorite = onFavorite,
            onClose = onClose,
        )
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

/**
 * 动作结果提示：下载进度/结果，或占位功能（收藏）的说明。
 *
 * 一行就够，不做 Toast 也不做弹窗：这类结果用户看完就走，弹窗还得再点一次关闭；
 * 而失败原因（网络/权限/存储）必须留在屏幕上，用户才有机会照着去处理。
 */
@Composable
private fun ActionStatusLine(state: HomeUiState) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    val message = when (val downloadState = state.downloadState) {
        DownloadUiState.Idle -> state.actionHint
        DownloadUiState.Downloading -> "正在保存到相册…"
        is DownloadUiState.Done -> downloadState.message
        is DownloadUiState.Failed -> downloadState.message
    }
    if (message.isNullOrBlank()) return

    // 进行中用更弱的字色，把注意力留给图片本身；结果用次要色，保证能读到
    val color = if (state.downloadState is DownloadUiState.Downloading) colors.textFaint else colors.textSecondary
    Text(
        text = message,
        color = color,
        fontSize = metrics.fontMicro,
        modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.spacingXs),
    )
}

/**
 * 底部动作条：下载（真功能）/ 收藏（占位）/ 关闭。
 *
 * 三个都用 Compact 尺寸挤在一行：栏宽只有 286dp，常规尺寸放三个会换行，
 * 换行后按钮离图片更远、更难点。
 *
 * 下载中把按钮文案改成"保存中…"并禁用：这是防重复点击的第一道闸，
 * 与状态层的"下载中直接返回"是两道独立的闸（按钮被绕过时状态层仍能挡住）。
 */
@Composable
private fun BottomActionBar(
    isDownloading: Boolean,
    isFavorite: Boolean,
    isFavoriteMutating: Boolean,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onClose: () -> Unit,
) {
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        AppPillButton(
            text = if (isDownloading) "保存中…" else "下载",
            onClick = onDownload,
            modifier = Modifier.weight(1f),
            size = PillSize.Compact,
            enabled = !isDownloading,
        )
        AppPillButton(
            // 三个状态一眼可分：未收藏="收藏"、已收藏="已收藏"（实心主色）、处理中="处理中…"（禁用）
            text = when {
                isFavoriteMutating -> "处理中…"
                isFavorite -> "已收藏"
                else -> "收藏"
            },
            onClick = onFavorite,
            modifier = Modifier.weight(1f),
            variant = if (isFavorite) PillVariant.Primary else PillVariant.Secondary,
            size = PillSize.Compact,
            enabled = !isFavoriteMutating,
        )
        AppPillButton(
            text = "关闭",
            onClick = onClose,
            modifier = Modifier.weight(1f),
            variant = PillVariant.Ghost,
            size = PillSize.Compact,
        )
    }
}
