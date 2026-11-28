// 主界面：顶栏 + 横向分类条 + 图片网格 + 底部固定状态栏。
//
// 这个文件只做编排：拿状态、摆组件、把用户操作转成回调。它不认识任何色值/字号/圆角，
// 也不知道数据从哪来（那是 state/ 与 data/ 的事），更不知道侧栏怎么滑动（那是 AppRoot 的事）。

package ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ImageItem
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
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.grid.GridCells
import com.tencent.kuikly.compose.foundation.lazy.grid.GridItemSpan
import com.tencent.kuikly.compose.foundation.lazy.grid.LazyVerticalGrid
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import theme.AppTheme
import ui.blocks.ImageCellBlock
import ui.blocks.colorFromHex
import ui.components.AppChip
import ui.components.AppEmpty
import ui.components.AppError
import ui.components.AppIconButton
import ui.components.AppLoading
import ui.screens.HomeLayout
import ui.screens.state.GridEntry
import ui.screens.state.HomeUiState
import ui.screens.state.ContentFilter

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenLeftSidebar: () -> Unit,
    onSelectFilter: (ContentFilter) -> Unit,
    onOpenImage: (ImageItem) -> Unit,
    // 点按图片 = 在 1×1 与 3×3 之间切换（长按才是打开右边栏，走 onOpenImage）
    onTogglePictureExpand: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    // 目录层级导航：进子文件夹 / 返回上级 / 面包屑跳回 / 展开压缩包
    onEnterFolder: (String) -> Unit,
    onGoUp: () -> Unit,
    onJumpToCrumb: (Int) -> Unit,
    onOpenArchive: (String) -> Unit,
    onCloseArchiveView: () -> Unit,
    // 压缩包动图动作（全部转给状态层，页面不做任何数据处理）
    onComposeZipGif: (String) -> Unit,
    onZipGifLoaded: (String, String) -> Unit,
    onZipGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
    // 把包内条目名拼成可渲染图片模型（由状态层拼 URL，UI 不自己拼）
    buildZipImages: (ArchiveEntry) -> List<ImageItem>,
    modifier: Modifier = Modifier,
    // 侧栏拖动由 AppRoot 构造后传进来，挂到网格区（避免抢顶部 chip 条的横向滚动）
    gridGestureModifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        TopBar(
            state = state,
            onOpenLeftSidebar = onOpenLeftSidebar,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
        )
        CategoryChipsRow(state = state, onSelectFilter = onSelectFilter)

        // 目录层级：面包屑 + 包内动图。只在分类视图出现（收藏/游客没有层级概念）
        if (state.isCategoryFilter) {
            if (state.breadcrumbs.isNotEmpty()) {
                BreadcrumbBar(
                    crumbs = state.breadcrumbs,
                    canGoUp = state.canGoUp,
                    onGoUp = onGoUp,
                    onCrumbClick = onJumpToCrumb,
                )
            }
            // 包内命中序列帧时，用动图卡替代平铺图片（合成在服务端）
            val openedArchive = state.openedArchive
            if (openedArchive != null && state.canSynthGif(openedArchive)) {
                ArchiveGifBanner(
                    entry = openedArchive,
                    gifState = state.gifStateOf(openedArchive),
                    onCompose = onComposeZipGif,
                    onLoaded = onZipGifLoaded,
                    onLoadFailed = onZipGifLoadFailed,
                    onRetry = onRetryZipGif,
                    modifier = Modifier.padding(horizontal = AppTheme.metrics.spacingLg),
                )
            }
        }

        // weight(1f)：网格吃掉顶栏与底栏之外的全部高度，翻页与滚动都在这块里发生
        Box(modifier = Modifier.weight(1f).fillMaxWidth().then(gridGestureModifier)) {
            HomeContent(
                state = state,
                onOpenImage = onOpenImage,
                onTogglePictureExpand = onTogglePictureExpand,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onEnterFolder = onEnterFolder,
                onGoUp = onGoUp,
                onJumpToCrumb = onJumpToCrumb,
                onOpenArchive = onOpenArchive,
                onCloseArchiveView = onCloseArchiveView,
                onComposeZipGif = onComposeZipGif,
                onZipGifLoaded = onZipGifLoaded,
                onZipGifLoadFailed = onZipGifLoadFailed,
                onRetryZipGif = onRetryZipGif,
                buildZipImages = buildZipImages,
            )
        }

        BottomStatusBar(state = state)
    }
}

/**
 * 顶栏：左 ☰ 开分类栏，中间胶囊显示站点名与连接状态，右 ⟳ 重试整条链路。
 *
 * 中间用「胶囊」而不是普通标题：它同时承载标题与在线状态，圆角底让这块信息在浅底上立起来，
 * 与设计稿顶栏一致。
 */
@Composable
private fun TopBar(
    state: HomeUiState,
    onOpenLeftSidebar: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.spacingLg, vertical = metrics.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        AppIconButton(onClick = onOpenLeftSidebar) {
            Text(text = HomeLayout.iconMenu, color = colors.textPrimary, fontSize = metrics.fontTitle)
        }

        TitleCapsule(state = state, modifier = Modifier.weight(1f))

        AppIconButton(onClick = onRetry) {
            Text(text = HomeLayout.iconRefresh, color = colors.textPrimary, fontSize = metrics.fontBody)
        }

        // 设置入口：换服务器地址用。放在顶栏最右，与"重试"相邻但不混同 ——
        // 重试是"再试一次当前配置"，设置是"我要换一个服务器"，两者的失败场景完全不同。
        AppIconButton(onClick = onOpenSettings) {
            Text(text = HomeLayout.iconSettings, color = colors.textPrimary, fontSize = metrics.fontBody)
        }
    }
}

// 胶囊标题：主标题一行 + 「状态点 + 状态/版本」一行。
@Composable
private fun TitleCapsule(state: HomeUiState, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(colors.surface)
            .padding(horizontal = metrics.spacingMd, vertical = metrics.spacingXs),
    ) {
        Text(
            text = state.serverTitle.ifBlank { DEFAULT_TITLE },
            color = colors.textPrimary,
            fontSize = metrics.fontSubtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(metrics.spacingXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(online = state.online)
            Spacer(modifier = Modifier.width(metrics.spacingXs))
            Text(
                text = "${state.statusText} · ${state.contentSubtitle}",
                color = colors.textFaint,
                fontSize = metrics.fontMicro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 横向分类条：不用打开侧栏就能连点着换分类。
 *
 * 用 LazyRow 而不是 Row：分类可能被用户配到十几二十个，一次性组合全部会拖慢首屏；
 * 而且这个版本没有 Modifier.horizontalScroll（Roll 2.17.0 未提供），能滚的只剩 Lazy 系。
 */
@Composable
private fun CategoryChipsRow(
    state: HomeUiState,
    onSelectFilter: (ContentFilter) -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    if (state.categories.isEmpty()) return

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = metrics.spacingLg),
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        // 用带 count 的成员重载而不是 items(list) 扩展：网格那边也导入了同名扩展，避免同名冲突
        items(count = state.categories.size) { index ->
            val category = state.categories[index]
            AppChip(
                label = category.label.ifBlank { category.name },
                selected = state.filter == ContentFilter.Category(category.name),
                dotColor = colorFromHex(category.color, colors.primary),
                onClick = { onSelectFilter(ContentFilter.Category(category.name)) },
            )
        }
    }
}

// 内容区四态：分类拉不到 / 分类加载中 / 图片页出错 / 图片页加载中 / 空 / 正常网格。
@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenImage: (ImageItem) -> Unit,
    // 点按图片 = 在 1×1 与 3×3 之间切换（长按才是打开右边栏，走 onOpenImage）
    onTogglePictureExpand: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEnterFolder: (String) -> Unit,
    onGoUp: () -> Unit,
    onJumpToCrumb: (Int) -> Unit,
    onOpenArchive: (String) -> Unit,
    onCloseArchiveView: () -> Unit,
    onComposeZipGif: (String) -> Unit,
    onZipGifLoaded: (String, String) -> Unit,
    onZipGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
    buildZipImages: (ArchiveEntry) -> List<ImageItem>,
) {
    val categoryError = state.categoryError
    val pageError = state.pageError

    // 收藏/游客视图没有"分类"概念，分类加载态与分类错误只对 All 视图有意义
    val showCategoryStates = state.isCategoryFilter

    when {
        showCategoryStates && categoryError != null -> AppError(
            modifier = Modifier.fillMaxSize(),
            title = "无法加载分类",
            hint = categoryError.userMessage,
            onRetry = onRetry,
        )

        showCategoryStates && state.isLoadingCategories -> AppLoading(modifier = Modifier.fillMaxSize())

        pageError != null -> AppError(
            modifier = Modifier.fillMaxSize(),
            title = "图片加载失败",
            hint = pageError.userMessage,
            onRetry = onRetry,
        )

        // 三视图共用一个加载态出口：画面一致，数据来源不同
        state.gridLoading -> AppLoading(modifier = Modifier.fillMaxSize())

        // 只有"图片与压缩包都空"才算空目录：分类下常有只有包没有图的情况，内容其实在下面
        state.isContentEmpty -> AppEmpty(
            modifier = Modifier.fillMaxSize(),
            title = state.gridEmptyTitle,
            hint = state.gridEmptyHint,
        )

        else -> ImageGrid(
            state = state,
            onOpenImage = onOpenImage,
            onTogglePictureExpand = onTogglePictureExpand,
            onLoadMore = onLoadMore,
            onEnterFolder = onEnterFolder,
            onOpenArchive = onOpenArchive,
            onComposeZipGif = onComposeZipGif,
            onZipGifLoaded = onZipGifLoaded,
            onZipGifLoadFailed = onZipGifLoadFailed,
            onRetryZipGif = onRetryZipGif,
            buildZipImages = buildZipImages,
        )
    }
}

@Composable
private fun ImageGrid(
    state: HomeUiState,
    onOpenImage: (ImageItem) -> Unit,
    onTogglePictureExpand: (String) -> Unit,
    onLoadMore: () -> Unit,
    onEnterFolder: (String) -> Unit,
    onOpenArchive: (String) -> Unit,
    onComposeZipGif: (String) -> Unit,
    onZipGifLoaded: (String, String) -> Unit,
    onZipGifLoadFailed: (String, String) -> Unit,
    onRetryZipGif: (String) -> Unit,
    buildZipImages: (ArchiveEntry) -> List<ImageItem>,
) {
    val metrics = AppTheme.metrics

    LazyVerticalGrid(
        columns = GridCells.Fixed(HomeLayout.gridColumns),
        modifier = Modifier.fillMaxSize(),
        // 四周留白用通用间距语义（16），不另立页面常量
        contentPadding = PaddingValues(metrics.spacingLg),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingXs),
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingXs),
    ) {
        // 网格条目三分支：图 / 文件夹 / 压缩包。渲染侧只按类型分发，
        // 不需要知道内容来自 /api/dir、收藏列表还是包内 —— 这正是 GridEntry 抽象的价值
        // span 按条目算：放大的那张跨满整行（3 列），配合 aspectRatio(1f) 宽 3 列即高 3 行
        items(
            count = state.gridEntries.size,
            span = { index ->
                val entry = state.gridEntries[index]
                if (entry is GridEntry.Picture && state.isExpanded(entry.item.absoluteUrl)) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(SINGLE_CELL_SPAN)
                }
            },
        ) { index ->
            when (val entry = state.gridEntries[index]) {
                is GridEntry.Picture -> {
                    val entryKey = entry.item.absoluteUrl
                    ImageCellBlock(
                        imageUrl = entry.item.absoluteUrl,
                        name = entry.item.name,
                        sizeText = entry.item.sizeText,
                        highlighted = state.isExpanded(entryKey),
                        // 点按 = 放大/收起；长按 = 打开右边栏（原点击行为，不能丢）
                        onClick = { onTogglePictureExpand(entryKey) },
                        onLongClick = { onOpenImage(entry.item) },
                    )
                }

                is GridEntry.Folder -> FolderCard(
                    name = entry.name,
                    count = entry.count,
                    onClick = { onEnterFolder(entry.name) },
                )


                is GridEntry.Archive -> ArchiveCard(
                    name = entry.name,
                    imageCount = entry.imageCount,
                    onClick = { onOpenArchive(entry.path) },
                )
            }
        }

        // 尾部一：分页哨兵（占满整行），只有分类视图有分页
        if (state.isCategoryFilter && (state.hasMore || state.isLoadingMore)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(
                    isLoadingMore = state.isLoadingMore,
                    loadedCount = state.loadedCount,
                    onLoadMore = onLoadMore,
                )
            }
        }

    }
}

/**
 * 触底加载的哨兵。
 *
 * 为什么要"哨兵"而不是监听滚动位置：滚动位置要靠 layoutInfo 推算"是否到底"，
 * 而哨兵是声明式的 —— 它被组合出来就说明尾部进入（或即将进入）视口，直接请求下一页。
 * 用 loadedCount 当 key：新一页装进来后 key 变了，哨兵若仍在视口内就再要一页（继续往下滚时自然续拉）；
 * 一旦新内容把它顶出视口，它就被回收，LaunchedEffect 随之取消，不会在后台无限拉页。
 */
@Composable
private fun LoadMoreFooter(
    isLoadingMore: Boolean,
    loadedCount: Int,
    onLoadMore: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    LaunchedEffect(loadedCount) {
        if (!isLoadingMore) onLoadMore()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = metrics.spacingMd),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoadingMore) {
            AppLoading(message = "正在加载更多")
        } else {
            Text(text = "继续下滑加载更多", color = colors.textGhost, fontSize = metrics.fontCaption)
        }
    }
}

// 底部固定栏：服务器名 + 在线状态 + 版本。永远可见，不随网格滚动。
@Composable
private fun BottomStatusBar(state: HomeUiState) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = metrics.spacingLg, vertical = metrics.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = HomeLayout.iconServer, fontSize = metrics.fontSubtitle)
        Spacer(modifier = Modifier.width(metrics.spacingSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.serverTitle.ifBlank { DEFAULT_TITLE },
                color = colors.textPrimary,
                fontSize = metrics.fontCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(online = state.online)
                Spacer(modifier = Modifier.width(metrics.spacingXs))
                Text(
                    text = buildStatusLine(state),
                    color = colors.textFaint,
                    fontSize = metrics.fontMicro,
                )
            }
        }
    }
}

// 状态点：在线用主色，离线用 ghost 色。形状用 radiusPill 画成圆，避免再引入 CircleShape。
@Composable
private fun StatusDot(online: Boolean) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    Box(
        modifier = Modifier
            .size(metrics.sizeDot)
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(if (online) colors.primary else colors.textGhost),
    )
}

// 状态行文案：有版本号才显示版本，避免出现「在线 · v」这种半截文本。
private fun buildStatusLine(state: HomeUiState): String {
    val version = state.serverVersion
    return if (version.isBlank()) state.statusText else "${state.statusText} · v$version"
}

// 站点标题兜底。正常情况后端一定给 title，这里只是防止加载中显示空白胶囊。
private const val DEFAULT_TITLE = "拾光集"

// 未放大的条目占 1 格；放大时跨满整行（列数由 HomeLayout.gridColumns 决定）
private const val SINGLE_CELL_SPAN = 1
