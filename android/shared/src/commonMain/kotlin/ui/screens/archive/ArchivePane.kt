// 压缩包视图的零件：标题条（含合成动图入口）、mod 包提示、GIF 展示区、压缩包列表。
//
// 这里全是"编排 + 展示"：不发请求、不算帧间隔、不解压。包内图片由页面用现有网格渲染，
// 所以本文件只提供网格之外的那几条横条与列表。

package ui.screens.archive

import androidx.compose.runtime.Composable
import com.eightfs.lumigather.data.ArchiveEntry
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.blocks.CategoryBlock
import ui.components.AppCard
import ui.components.AppEmpty
import ui.components.AppListRow
import ui.components.AppLoading
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.GifState
import ui.screens.state.HomeUiState

/**
 * 标题条：当前在压缩包列表 / 某个包内，以及两个动作（合成动图、返回列表）。
 *
 * 「合成动图」只在 [HomeUiState.canComposeGif] 为真时出现 —— 每个目录都摆一个按钮，
 * 用户点进去九成是"没有序列帧"，那种按钮只会教人放弃使用。
 */
@Composable
internal fun ArchiveHeader(
    state: HomeUiState,
    onComposeGif: () -> Unit,
    onCloseArchive: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val opened = state.openedArchive
    val isGenerating = state.gifState is GifState.Generating

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.spacingLg, vertical = metrics.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        Text(
            text = opened?.name ?: "压缩包",
            color = colors.textPrimary,
            fontSize = metrics.fontCaption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.canComposeGif) {
            AppPillButton(
                // 生成中改文案 + 禁用：服务端合成要几秒，连点会排出多个任务
                text = if (isGenerating) "合成中…" else "合成动图",
                onClick = onComposeGif,
                size = PillSize.Compact,
                enabled = !isGenerating,
            )
        }
        if (opened != null) {
            AppPillButton(
                text = "返回列表",
                onClick = onCloseArchive,
                variant = PillVariant.Secondary,
                size = PillSize.Compact,
            )
        }
    }
}

/**
 * mod 包提示：包里含 .ini 说明它是 3DMigoto mod。
 *
 * 说明里刻意写"由服务端判定翻转"而不是"已翻转"：真正的翻转是按 flip-rule 逐图判的，
 * 包里可能只有 desktop.ini 这类元数据文件，写死"已翻转"会让人以为图被翻错了。
 */
@Composable
internal fun ArchiveIniNotice(entry: ArchiveEntry) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    if (!entry.hasIni) return

    val speedText = entry.speed?.let { speed -> " · 动画倍速 $speed" }.orEmpty()
    Text(
        text = "这是 3DMigoto mod 包（含 .ini）$speedText；贴图是否需要翻转由服务端逐图判定",
        color = colors.textFaint,
        fontSize = metrics.fontMicro,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.spacingLg, vertical = metrics.spacingXs),
    )
}

/**
 * GIF 展示区：生成中显示加载态，成功显示动图，失败显示中文原因。
 *
 * 动图"能不能显示"由图片加载器决定 —— 我们只负责把 URL 交出去，并把加载结果回传给状态层
 * （onSuccess/onError 就是"合成完成/失败"的唯一可信信号）。
 */
@Composable
internal fun ArchiveGifArea(
    state: HomeUiState,
    onGifLoaded: (String) -> Unit,
    onGifLoadFailed: (String) -> Unit,
    onDismissGif: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    when (val gifState = state.gifState) {
        GifState.Idle -> Unit

        is GifState.Generating, is GifState.Ready -> {
            val url = when (gifState) {
                is GifState.Generating -> gifState.url
                is GifState.Ready -> gifState.url
                else -> ""
            }
            val generating = gifState is GifState.Generating

            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.spacingLg),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (generating) "正在合成动图…（服务端处理中）" else "合成结果",
                        color = colors.textSecondary,
                        fontSize = metrics.fontCaption,
                        modifier = Modifier.weight(1f),
                    )
                    AppPillButton(
                        text = "关闭",
                        onClick = onDismissGif,
                        variant = PillVariant.Ghost,
                        size = PillSize.Compact,
                    )
                }
                Spacer(modifier = Modifier.height(metrics.spacingXs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HomeLayout.previewHeight)
                        .clip(RoundedCornerShape(metrics.radiusMd))
                        .background(colors.container),
                    contentAlignment = Alignment.Center,
                ) {
                    if (generating) {
                        // 加载态与图片并存：加载器一旦拿到字节就会把动图画上来
                        AppLoading(message = "")
                    }
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = url,
                            onSuccess = { onGifLoaded(url) },
                            onError = { onGifLoadFailed(url) },
                        ),
                        contentDescription = "合成的动图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        is GifState.Failed -> {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.spacingLg),
            ) {
                Text(
                    text = gifState.message,
                    color = colors.textPrimary,
                    fontSize = metrics.fontCaption,
                )
                Spacer(modifier = Modifier.height(metrics.spacingXs))
                AppPillButton(
                    text = "知道了",
                    onClick = onDismissGif,
                    variant = PillVariant.Ghost,
                    size = PillSize.Compact,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(metrics.spacingXs))
}

/**
 * 压缩包列表。
 *
 * 复用分类列表的 AppListRow 形态：左边一个字形、中间包名 + 图片张数、右边提示是否 mod 包。
 * 数量用「N 张」而不是数字胶囊 —— 这里说的是"包里有多少图"，与分类的图数语义不同。
 */
@Composable
internal fun ArchiveListPane(
    state: HomeUiState,
    onOpenArchive: (ArchiveEntry) -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (state.archivesLoading) {
        AppLoading(modifier = Modifier.fillMaxSize(), message = "正在扫描压缩包")
        return
    }
    if (state.archives.isEmpty()) {
        AppEmpty(
            modifier = Modifier.fillMaxSize(),
            title = "这个目录里没有压缩包",
            hint = "支持 zip / rar / 7z；换一个分类看看",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = metrics.spacingLg, vertical = metrics.spacingSm),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        items(count = state.archives.size) { index ->
            val entry = state.archives[index]
            AppListRow(
                title = entry.name,
                subtitle = archiveSubtitle(entry),
                onClick = { onOpenArchive(entry) },
                leading = {
                    Text(
                        text = GLYPH_ARCHIVE,
                        color = colors.textSecondary,
                        fontSize = metrics.fontSubtitle,
                    )
                },
                value = "${entry.images.size} 张",
            )
        }
    }
}

// 副标题：把"是不是 mod 包"用文字说清楚，而不是只靠右边一个数字
private fun archiveSubtitle(entry: ArchiveEntry): String {
    val kind = if (entry.hasIni) "3DMigoto mod 包" else "普通图包"
    val speed = entry.speed?.let { " · 倍速 $it" }.orEmpty()
    return "$kind$speed"
}

// 压缩包的字形图标：与左栏一致，不引图标库
private const val GLYPH_ARCHIVE = "🗜"
