// 当前目录的压缩包区块：图片网格之后追加的一组可折叠包。
//
// 形态对齐网页版（server/public/app.js 的 .zip-wrap / .zip-group）：
//   - 每个包一组：标题行（折叠箭头 + 图标 + 包名 + 张数，可点折叠）+ 包内内容
//   - 默认展开；收起状态由状态层记住（按包 path），换目录保留、换服务器清空
//   - 只渲染"有图片"的包（空包点开是空的，占位只会让人以为界面坏了）
//   - 包内命中序列帧时显示一张动图卡（不是平铺），否则平铺图片，卡片与普通图同款
//
// 这里全是编排 + 展示：不拼 URL（由状态层的 zipImagesOf 拼）、不解压、不编码 GIF。

package ui.screens.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ImageItem
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.blocks.ImageCellBlock
import ui.components.AppLoading
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.GifState
import ui.screens.state.HomeUiState

/**
 * 压缩包区块。放在图片网格的尾部（占满整行），没有可展示的包时**什么都不渲染** ——
 * 不是"显示一个空区块"，那样每个没有包的目录都会多一条横线。
 */
@Composable
fun ZipSection(
    state: HomeUiState,
    buildZipImages: (ArchiveEntry) -> List<ImageItem>,
    onToggleArchive: (String) -> Unit,
    onComposeZipGif: (String) -> Unit,
    onGifLoaded: (String, String) -> Unit,
    onGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
    onOpenImage: (ImageItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = AppTheme.metrics
    val zips = state.visibleArchives
    // 没有包也没有错误时才整块不渲染：没有包的目录不该多出一条空白横条
    if (zips.isEmpty() && state.archiveScanError == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        // 扫描失败单独一行（局部提示）：包没出来时用户需要知道是"这目录没包"还是"扫不动"
        state.archiveScanError?.let { message ->
            Text(
                text = message,
                color = AppTheme.colors.textFaint,
                fontSize = AppTheme.metrics.fontMicro,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        zips.forEach { entry ->
            ZipGroup(
                state = state,
                entry = entry,
                buildZipImages = buildZipImages,
                onToggleArchive = onToggleArchive,
                onComposeZipGif = onComposeZipGif,
                onGifLoaded = onGifLoaded,
                onGifLoadFailed = onGifLoadFailed,
                onRetryZipGif = onRetryZipGif,
                onOpenImage = onOpenImage,
            )
        }
    }
}

// 一个包：标题行 + 包内内容。收起时只留标题行（包内图片不加载，省内存也省服务端解压）
@Composable
private fun ZipGroup(
    state: HomeUiState,
    entry: ArchiveEntry,
    buildZipImages: (ArchiveEntry) -> List<ImageItem>,
    onToggleArchive: (String) -> Unit,
    onComposeZipGif: (String) -> Unit,
    onGifLoaded: (String, String) -> Unit,
    onGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
    onOpenImage: (ImageItem) -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val collapsed = state.isArchiveCollapsed(entry)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.radiusMd))
            .background(colors.container),
    ) {
        ZipGroupHead(
            entry = entry,
            collapsed = collapsed,
            onClick = { onToggleArchive(entry.path) },
        )
        if (!collapsed) {
            if (state.canSynthGif(entry)) {
                ZipGifCard(
                    entry = entry,
                    gifState = state.gifStateOf(entry),
                    onComposeZipGif = onComposeZipGif,
                    onGifLoaded = onGifLoaded,
                    onGifLoadFailed = onGifLoadFailed,
                    onRetryZipGif = onRetryZipGif,
                )
            } else {
                ZipImageGrid(images = buildZipImages(entry), onOpenImage = onOpenImage)
            }
        }
    }
}

// 标题行：折叠箭头 + 包图标 + 包名 + mod 标记 + 张数。整行可点，与网页版 zip-group-head 一致
@Composable
private fun ZipGroupHead(entry: ArchiveEntry, collapsed: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = metrics.spacingSm, vertical = metrics.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        Text(
            text = if (collapsed) GLYPH_COLLAPSED else GLYPH_EXPANDED,
            color = colors.textSecondary,
            fontSize = metrics.fontCaption,
        )
        Text(text = GLYPH_ARCHIVE, color = colors.textSecondary, fontSize = metrics.fontCaption)
        Text(
            text = entry.name,
            color = colors.textPrimary,
            fontSize = metrics.fontCaption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // mod 包标记：包里含 .ini 说明是 3DMigoto mod（贴图是否翻转由服务端逐图判定，这里只提示性质）
        if (entry.hasIni) {
            Text(text = "mod", color = colors.textFaint, fontSize = metrics.fontMicro)
        }
        Text(
            text = "${entry.images.size} 张",
            color = colors.textSecondary,
            fontSize = metrics.fontMicro,
        )
    }
}

/**
 * 包内内容的"动图卡"。
 *
 * 与网页版一致：命中序列帧的包**不在网格里平铺图片**，而是直接显示一张动图卡。
 * 动图由服务端合成（几秒），所以界面必须能表达"正在生成 / 失败原因"：
 * - Idle：展开即自动请求一次（LaunchedEffect 随折叠状态挂载/卸载，收起不会白跑）
 * - Generating：显示加载态 + 同时挂上 Image（加载器拿到字节就会画出来）
 * - Ready：只显示动图
 * - Failed：显示服务端原因 + 重试（重试不会再自动触发，避免失败后无限重试）
 */
@Composable
private fun ZipGifCard(
    entry: ArchiveEntry,
    gifState: GifState,
    onComposeZipGif: (String) -> Unit,
    onGifLoaded: (String, String) -> Unit,
    onGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (gifState is GifState.Idle) {
        LaunchedEffect(entry.path) { onComposeZipGif(entry.path) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(metrics.spacingSm)) {
        when (gifState) {
            GifState.Idle -> AppLoading(modifier = Modifier.fillMaxWidth(), message = "正在准备动图")

            is GifState.Failed -> {
                Text(
                    text = gifState.message,
                    color = colors.textPrimary,
                    fontSize = metrics.fontCaption,
                )
                Spacer(modifier = Modifier.height(metrics.spacingXs))
                AppPillButton(
                    text = "重试",
                    onClick = { onRetryZipGif(entry.path) },
                    variant = PillVariant.Ghost,
                    size = PillSize.Compact,
                )
            }

            is GifState.Generating -> {
                Text(
                    text = "正在合成动图…（服务端处理中）",
                    color = colors.textSecondary,
                    fontSize = metrics.fontMicro,
                )
                Spacer(modifier = Modifier.height(metrics.spacingXs))
                GifImage(
                    url = gifState.url,
                    onLoaded = { url -> onGifLoaded(entry.path, url) },
                    onFailed = { url -> onGifLoadFailed(entry.path, url) },
                )
            }

            is GifState.Ready -> GifImage(
                url = gifState.url,
                onLoaded = { url -> onGifLoaded(entry.path, url) },
                onFailed = { url -> onGifLoadFailed(entry.path, url) },
            )
        }
    }
}

// 动图本体：加载成功/失败都回传给状态层（"合成完成"只有图片层知道）
@Composable
private fun GifImage(url: String, onLoaded: (String) -> Unit, onFailed: (String) -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeLayout.previewHeight)
            .clip(RoundedCornerShape(metrics.radiusSm))
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = url,
                onSuccess = { onLoaded(url) },
                onError = { onFailed(url) },
            ),
            contentDescription = "合成的动图",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 包内图片：按网格列数分行平铺，卡片与普通图同款（可点开详情、可下载）。
 *
 * 为什么不用 LazyVerticalGrid：这里已经在一个 Lazy 网格的尾部 item 内，嵌套懒加载会互相抢滚动。
 * 包内图片数量通常有限，直接按行铺开即可；若将来遇到超大包再改成"先渲染前 N 张 + 展开更多"。
 */
@Composable
private fun ZipImageGrid(images: List<ImageItem>, onOpenImage: (ImageItem) -> Unit) {
    val metrics = AppTheme.metrics
    val columns = HomeLayout.gridColumns

    Column(
        modifier = Modifier.fillMaxWidth().padding(metrics.spacingSm),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        images.chunked(columns).forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
            ) {
                rowImages.forEach { image ->
                    ImageCellBlock(
                        imageUrl = image.absoluteUrl,
                        name = image.name,
                        sizeText = image.sizeText,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenImage(image) },
                    )
                }
                // 末行不足一行时补空位，避免最后一张被拉宽
                repeat(columns - rowImages.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// 折叠指示与包图标：本项目不引图标库，与左栏/分类行同一种线性字形风格
private const val GLYPH_EXPANDED = "▾"
private const val GLYPH_COLLAPSED = "▸"
private const val GLYPH_ARCHIVE = "🗜"
