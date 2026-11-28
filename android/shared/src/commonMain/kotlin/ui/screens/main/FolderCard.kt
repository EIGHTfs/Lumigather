// 网格里的"非图片"卡片：文件夹、压缩包，以及目录层级用的面包屑条与包内动图卡。
//
// 为什么单独成文件：这些卡片是"网格条目"这个抽象的具体形态之一，和图片卡片并列；
// 放在 HomeScreen 里会让那个文件同时承担"布局编排"和"卡片外观"两件事。
//
// 三条视觉约定（与图片卡片对齐）：
//   - 都用 aspectRatio(1f)：与图片格子同尺寸，混排时行高一致，不会出现"文件夹矮一截"
//   - 文件夹用中性色；压缩包用**主色描边 + 主色图标**区分（同为容器，不该长得一样）
//   - 张数未知（count < 0）显示「目录」而不是「0 张」—— 后端未统计不等于没有图

package ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.eightfs.lumigather.data.ArchiveEntry
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.components.AppCard
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.GifState

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

/** 压缩包卡片：与文件夹同尺寸，主色描边区分。 */
@Composable
fun ArchiveCard(
    name: String,
    imageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusMd)

    AppCard(
        modifier = modifier
            .aspectRatio(1f)
            .border(metrics.thicknessRule, colors.primary, shape)
            .clickable(onClick = onClick),
        padding = metrics.spacingSm,
    ) {
        Text(text = GLYPH_ARCHIVE, color = colors.primary, fontSize = metrics.fontSubtitle)
        Spacer(Modifier.height(metrics.spacingXs))
        Text(
            text = name,
            color = colors.textPrimary,
            fontSize = metrics.fontCaption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(text = "$imageCount 张 · 压缩包", color = colors.textFaint, fontSize = metrics.fontMicro)
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

/**
 * 包内动图卡（包内命中序列帧时替代平铺图片）。
 *
 * 与服务端约定的分工：合成与编码在服务端，这里只负责"把 URL 交给图片加载器 + 表达三种过程状态"。
 * 展开即自动请求一次（LaunchedEffect 随组合挂载），失败后不再自动重试（避免失败时反复打服务端）。
 */
@Composable
fun ArchiveGifBanner(
    entry: ArchiveEntry,
    gifState: GifState,
    onCompose: (String) -> Unit,
    onLoaded: (String, String) -> Unit,
    onLoadFailed: (String, String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (gifState is GifState.Idle) {
        LaunchedEffect(entry.path) { onCompose(entry.path) }
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        when (gifState) {
            GifState.Idle -> Text(
                text = GIF_PREPARING_TEXT,
                color = colors.textSecondary,
                fontSize = metrics.fontCaption,
            )

            is GifState.Failed -> {
                Text(text = gifState.message, color = colors.textPrimary, fontSize = metrics.fontCaption)
                Spacer(Modifier.height(metrics.spacingXs))
                AppPillButton(
                    text = RETRY_LABEL,
                    onClick = { onRetry(entry.path) },
                    variant = PillVariant.Ghost,
                    size = PillSize.Compact,
                )
            }

            is GifState.Generating, is GifState.Ready -> {
                val url = when (gifState) {
                    is GifState.Generating -> gifState.url
                    is GifState.Ready -> gifState.url
                    else -> ""
                }
                if (gifState is GifState.Generating) {
                    Text(
                        text = GIF_GENERATING_TEXT,
                        color = colors.textSecondary,
                        fontSize = metrics.fontMicro,
                    )
                    Spacer(Modifier.height(metrics.spacingXs))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HomeLayout.previewHeight)
                        .clip(RoundedCornerShape(metrics.radiusSm))
                        .background(colors.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = url,
                            onSuccess = { onLoaded(entry.path, url) },
                            onError = { onLoadFailed(entry.path, url) },
                        ),
                        contentDescription = GIF_CONTENT_DESCRIPTION,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// 字形图标：本项目不引图标库，与左栏/分类行保持同一种线性字形风格
private const val GLYPH_FOLDER = "📁"
private const val GLYPH_ARCHIVE = "🗜"
private const val UNKNOWN_COUNT_TEXT = "目录"
private const val BACK_LABEL = "‹ 上级"
private const val CRUMB_SEPARATOR = "/"
private const val RETRY_LABEL = "重试"
private const val GIF_PREPARING_TEXT = "正在准备动图"
private const val GIF_GENERATING_TEXT = "正在合成动图…（服务端处理中）"
private const val GIF_CONTENT_DESCRIPTION = "合成的动图"
