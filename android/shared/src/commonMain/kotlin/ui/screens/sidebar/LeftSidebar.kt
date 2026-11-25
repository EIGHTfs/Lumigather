// 左侧分类栏：站点名标题 + 搜索框 + 分区标题 + 分类列表 + 底部状态。
//
// 它只负责"把分类画成一列并接受点击"，滑动与遮罩由 AppRoot 负责，
// 数据与张数统计由 state/ 负责。所以这个文件里既没有网络调用，也没有位移逻辑。

package ui.screens.sidebar

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.Canvas
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import theme.AppTheme
import ui.components.AppCountBadge
import ui.components.formatCountThousands
import ui.blocks.CategoryBlock
import ui.components.AppEmpty
import ui.components.AppIconButton
import ui.components.AppListRow
import ui.screens.HomeLayout
import ui.screens.state.HomeUiState
import ui.screens.state.HomeView

@Composable
fun LeftSidebar(
    state: HomeUiState,
    onSelectCategory: (String) -> Unit,
    onSelectView: (HomeView) -> Unit,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    // 只有右侧两角做圆角：面板从左侧边缘滑出，左边贴边、右边浮起，视觉上才像"抽屉"。
    // 这里的 0.dp 是"这一侧不要圆角"的零值，不是设计参数，故不抽常量。
    val shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = metrics.radiusLg,
        bottomEnd = metrics.radiusLg,
        bottomStart = 0.dp,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.surface)
            .padding(horizontal = metrics.spacingSm, vertical = metrics.spacingMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
    ) {
        SidebarHeader(
            siteTitle = state.serverTitle.ifBlank { FALLBACK_SITE_TITLE },
            onClose = onClose,
        )
        SearchField(query = state.searchQuery, onQueryChange = onQueryChange)
        // 两个虚拟视图放在分类分区之上：它们是"另一套数据源"，不是某个分类，位置也要在视觉上先于分类
        VirtualViewRows(state = state, onSelectView = onSelectView)
        SectionTitle(
            text = sectionTitle(
                totalCategoryCount = state.categories.size,
                visibleCategoryCount = state.visibleCategories.size,
                searching = state.searchQuery.isNotBlank(),
            ),
        )
        // weight 只有在 Column 作用域里才可用，所以必须在这里传进去，不能让列表函数自己带
        CategoryList(
            state = state,
            onSelectCategory = onSelectCategory,
            modifier = Modifier.weight(1f),
        )
        SidebarFooter(state = state)
    }
}

/**
 * 栏头：站点名 + 关闭。
 *
 * 标题用站点名而不是固定的「分类」：用户在这一栏里挑的是"这台服务器上有哪些游戏"，
 * 站点名先回答"我在看谁的库"，分类列表才是答案。
 */
@Composable
private fun SidebarHeader(siteTitle: String, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = siteTitle,
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
 * 分区标题：「全部游戏 · N 个分类」。
 *
 * 作用不是装饰，而是回答"下面这一列是什么、有多少"：分类数量在加载完成前是未知的，
 * 有了这行用户不必自己数。搜索时改说「搜索结果」，避免明明只筛出 2 条还写着"8 个分类"。
 */
@Composable
private fun SectionTitle(text: String) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = text,
        color = colors.textFaint,
        fontSize = metrics.fontMicro,
        modifier = Modifier.padding(start = metrics.spacingXs),
    )
}

/**
 * 搜索框：容器底 + 全圆角，里面是「线性放大镜 + 输入区」。
 *
 * 用 BasicTextField 而不是 material3 的 TextField：后者自带 48dp 高度、浮动标签与描边，
 * 塞不进侧栏这个紧凑的胶囊，硬用会把整栏撑高。占位文案自己叠一层，
 * 而不是走 decorationBox，是为了少依赖一个参数复杂的行为（占位只是视觉，不影响输入）。
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(colors.container)
            .padding(horizontal = metrics.spacingMd, vertical = metrics.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon()
        Spacer(modifier = Modifier.width(metrics.spacingSm))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索分类",
                    color = colors.textGhost,
                    fontSize = metrics.fontSubtitle,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = metrics.fontSubtitle),
                cursorBrush = SolidColor(colors.primary),
            )
        }
    }
}

/**
 * 线性放大镜：一个描边圆 + 一根 45° 手柄。
 *
 * 为什么自己画而不是用字形：emoji 放大镜是彩色位图字形，在不同 ROM 上渲染出来的
 * 颜色、粗细、留白都不一样，甚至会盖住旁边文字；而文字里可用的非 emoji 放大镜字形
 * 大多字体缺失，会退化成方框。用 Canvas 画两条笔画，颜色取自主题、粗细取自令牌，
 * 全平台一致且不依赖任何字体或图标库。
 */
@Composable
private fun SearchIcon() {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val strokeWidth = metrics.thicknessRule

    Canvas(modifier = Modifier.size(metrics.sizeInlineIcon)) {
        val strokePx = strokeWidth.toPx()
        val lensRadius = size.minDimension * LENS_RADIUS_RATIO
        val lensCenter = Offset(
            x = size.width * LENS_CENTER_RATIO,
            y = size.height * LENS_CENTER_RATIO,
        )

        drawCircle(
            color = colors.textFaint,
            radius = lensRadius,
            center = lensCenter,
            style = Stroke(width = strokePx),
        )

        // 手柄从镜片边缘沿 45° 伸出：起点落在圆周上，终点接近图标方框的右下角
        val handleOffset = lensRadius * DIAGONAL_UNIT
        drawLine(
            color = colors.textFaint,
            start = Offset(lensCenter.x + handleOffset, lensCenter.y + handleOffset),
            end = Offset(size.width * HANDLE_END_RATIO, size.height * HANDLE_END_RATIO),
            strokeWidth = strokePx,
        )
    }
}

// 分类列表：搜索为空时是全部分类，有关键字时只剩匹配项。
@Composable
private fun CategoryList(
    state: HomeUiState,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = AppTheme.metrics
    val visible = state.visibleCategories

    if (visible.isEmpty()) {
        AppEmpty(
            modifier = modifier.fillMaxWidth(),
            title = "没有匹配的分类",
            hint = "换个关键词试试",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = metrics.spacingXs),
        // 行间距从 spacingXs 提到 spacingSm：设计稿里行与行之间更透气，
        // 且不放宽行内边距（AppListRow 的内部留白是共用样式，不在本轮改动范围）
        verticalArrangement = Arrangement.spacedBy(metrics.spacingSm),
    ) {
        // 用带 count 的成员重载而不是 items(list) 扩展：与页面其它列表一致，
        // 也避免与网格的 items 扩展函数同名冲突。
        items(count = visible.size) { index ->
            val category = visible[index]
            CategoryBlock(
                name = category.name,
                label = category.label.ifBlank { category.name },
                color = category.color,
                imageCount = category.imageCount,
                // 只有分类视图下分类才算选中：收藏/游客被选中时，全部分类行自动变回未选中，
                // 互斥不靠"切视图时记得清 selectedCategory"，而是靠这里的一次判断
                selected = state.view == HomeView.All && category.name == state.selectedCategory,
                countState = category.countState,
                onClick = { onSelectCategory(category.name) },
            )
        }
    }
}

/**
 * 两个虚拟视图入口：收藏 / 游客上传。
 *
 * 它们不是"分类"，而是"另一套数据源"，所以放在分类分区之上、独立成块；
 * 选中态复用分类行同一套视觉（淡主色底 + 尾部胶囊变主色），三个视图和分类列表看起来是一家人。
 *
 * 数量直接用已加载列表的条数：它来自服务端列表本身，不会出现"数字说有 12 张、点进去是空的"。
 */
@Composable
private fun VirtualViewRows(state: HomeUiState, onSelectView: (HomeView) -> Unit) {
    val metrics = AppTheme.metrics

    Column(verticalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
        VirtualViewRow(
            glyph = GLYPH_FAVORITE,
            title = "收藏",
            count = state.favoriteImages.size,
            selected = state.view == HomeView.Favorites,
            onClick = { onSelectView(HomeView.Favorites) },
        )
        VirtualViewRow(
            glyph = GLYPH_GUEST,
            title = "游客",
            count = state.guestImages.size,
            selected = state.view == HomeView.Guest,
            onClick = { onSelectView(HomeView.Guest) },
        )
        VirtualViewRow(
            glyph = GLYPH_ARCHIVE,
            title = "压缩包",
            // 数字是"当前分类目录下扫到的包个数"，进视图时才扫描，所以未进过就是 0
            count = state.archives.size,
            selected = state.view == HomeView.Archive,
            onClick = { onSelectView(HomeView.Archive) },
        )
    }
}

// 一行虚拟视图：字形 + 名称 + 数量胶囊。与分类行同构，只是没有色标。
@Composable
private fun VirtualViewRow(
    glyph: String,
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    AppListRow(
        title = title,
        selected = selected,
        // 选中信号交给尾部胶囊变主色（与分类行一致），不再额外挂圆点
        showSelectionDot = false,
        onClick = onClick,
        leading = {
            // 字形图标而不是图片资源：本项目不引图标库，分类行也是同一种线性风格
            Text(text = glyph, color = colors.textSecondary, fontSize = metrics.fontSubtitle)
        },
        trailing = { AppCountBadge(text = formatCountThousands(count), highlighted = selected) },
    )
}

/**
 * 侧栏底部摘要：只留连接状态。
 *
 * 站点名已经上移到栏头，分类数量已经上移到分区标题 —— 这里再写一遍就是同一屏里
 * 同一件事说两次。底部现在只负责"这台服务器现在通不通"。
 */
@Composable
private fun SidebarFooter(state: HomeUiState) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = if (state.online) STATUS_ONLINE else STATUS_OFFLINE,
        color = if (state.online) colors.textSecondary else colors.textGhost,
        fontSize = metrics.fontMicro,
        modifier = Modifier.fillMaxWidth().padding(start = metrics.spacingXs),
    )
}

/** 分区标题：全部 / 搜索结果 */
private fun sectionTitle(
    totalCategoryCount: Int,
    visibleCategoryCount: Int,
    searching: Boolean,
): String {
    val count = if (searching) visibleCategoryCount else totalCategoryCount
    val label = if (searching) SECTION_LABEL_SEARCH else SECTION_LABEL_ALL
    return "$label$SECTION_SEPARATOR$count$SECTION_UNIT"
}

/** 站点名取不到时的兜底文案（服务端 title 为空说明配置没填） */
private const val FALLBACK_SITE_TITLE = "拾光集"

private const val SECTION_LABEL_ALL = "全部游戏"
private const val SECTION_LABEL_SEARCH = "搜索结果"
private const val SECTION_SEPARATOR = " · "
private const val SECTION_UNIT = " 个分类"

private const val STATUS_ONLINE = "服务器在线"
private const val STATUS_OFFLINE = "服务器离线"

// 放大镜几何比例：镜片占图标的三成、圆心偏左上，右下留给手柄，
// 这样 16dp 的小方框里既有圆又有方向感，不至于糊成一团。
private const val LENS_RADIUS_RATIO = 0.30f
private const val LENS_CENTER_RATIO = 0.40f
private const val DIAGONAL_UNIT = 0.707f
private const val HANDLE_END_RATIO = 0.94f

// 虚拟视图的字形图标。放文件内常量而不是 HomeLayout：它们是左栏专有，
// 且 HomeLayout 本轮不在改动范围内（避免为两个字形去动共享布局常量）。
private const val GLYPH_FAVORITE = "♥"
private const val GLYPH_GUEST = "☁"
private const val GLYPH_ARCHIVE = "🗜"
