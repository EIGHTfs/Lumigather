// 左侧分类栏：搜索框 + 分类列表 + 底部状态。
//
// 它只负责"把分类画成一列并接受点击"，滑动与遮罩由 AppRoot 负责，
// 数据与张数统计由 state/ 负责。所以这个文件里既没有网络调用，也没有位移逻辑。

package ui.screens.sidebar

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import theme.AppTheme
import ui.blocks.CategoryBlock
import ui.components.AppEmpty
import ui.components.AppIconButton
import ui.screens.HomeLayout
import ui.screens.state.HomeUiState

@Composable
fun LeftSidebar(
    state: HomeUiState,
    onSelectCategory: (String) -> Unit,
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
        SidebarHeader(title = "分类", onClose = onClose)
        SearchField(query = state.searchQuery, onQueryChange = onQueryChange)
        // weight 只有在 Column 作用域里才可用，所以必须在这里传进去，不能让列表函数自己带
        CategoryList(
            state = state,
            onSelectCategory = onSelectCategory,
            modifier = Modifier.weight(1f),
        )
        SidebarFooter(state = state)
    }
}

@Composable
private fun SidebarHeader(title: String, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
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
 * 搜索框：容器底 + 全圆角，里面是「图标 + 输入区」。
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
        Text(text = HomeLayout.iconSearch, fontSize = metrics.fontSubtitle)
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
        verticalArrangement = Arrangement.spacedBy(metrics.spacingXs),
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
                selected = category.name == state.selectedCategory,
                countState = category.countState,
                onClick = { onSelectCategory(category.name) },
            )
        }
    }
}

// 侧栏底部摘要：站点名 + 连接状态 + 分类数量。与列表分离，列表滚动时它不动。
@Composable
private fun SidebarFooter(state: HomeUiState) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = state.serverTitle.ifBlank { "拾光集" },
            color = colors.textSecondary,
            fontSize = metrics.fontCaption,
        )
        Text(
            text = "${if (state.online) "服务器在线" else "服务器离线"} · 共 ${state.categories.size} 个分类",
            color = colors.textFaint,
            fontSize = metrics.fontMicro,
        )
    }
}
