// 服务器地址设置界面：首次启动的入口，也是运行中改地址的入口。
//
// 设计取舍：
// 1) 全屏而不是弹层 —— 未配置地址时这是唯一可做的事，弹层会让人以为"关掉就能进主界面"。
// 2) 内容用 LazyColumn —— Kuikly 2.17.0 没有 Modifier.verticalScroll（本项目实测过），
//    要能滚只能上 Lazy 系；键盘弹起时小屏也能滚到下面的按钮。
// 3) 输入用 BasicTextField —— Kuikly compose 的 material3 有 TextField，但它自带固定高度与
//    浮动标签，尺寸和本项目"胶囊输入框"的视觉不一致；BasicTextField 已在本工程侧栏搜索框用过。

package ui.screens.settings

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
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.text.TextStyle
import theme.AppTheme
import ui.components.AppCard
import ui.components.AppIconButton
import ui.components.AppPillButton
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.ConnectionTestState
import ui.screens.state.HomeUiState

@Composable
fun ServerSettingsScreen(
    state: HomeUiState,
    onAddressChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val isTesting = state.testState is ConnectionTestState.Testing

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(metrics.spacingLg),
        verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
    ) {
        // 返回入口只在"已经有可用配置"时给：首次启动时退出去也没有内容可看
        item { SettingsHeader(canClose = state.hasSavedAddress, onClose = onClose) }

        item {
            AppCard {
                AddressField(
                    address = state.addressDraft,
                    errorMessage = state.addressError,
                    onAddressChange = onAddressChange,
                )
            }
        }

        item { ConnectionStatusCard(state = state) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
                AppPillButton(
                    text = if (isTesting) "连接中…" else "测试连接",
                    onClick = onTestConnection,
                    modifier = Modifier.weight(1f),
                    variant = PillVariant.Secondary,
                    enabled = !isTesting,
                )
                AppPillButton(
                    text = "保存并进入",
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting,
                )
            }
        }

        item { SettingsHint() }
    }
}

// 标题区：一句说明"这是干什么的"，避免用户把地址填成网页版收藏夹里的完整页面地址。
@Composable
private fun SettingsHeader(canClose: Boolean, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "连接服务器", color = colors.textPrimary, fontSize = metrics.fontDisplay)
            Spacer(modifier = Modifier.height(metrics.spacingXs))
            Text(
                text = "填拾光集服务端在局域网里的地址，填完可以先测试再保存",
                color = colors.textFaint,
                fontSize = metrics.fontCaption,
            )
        }
        if (canClose) {
            AppIconButton(onClick = onClose) {
                Text(
                    text = HomeLayout.iconClose,
                    color = colors.textPrimary,
                    fontSize = metrics.fontSubtitle,
                )
            }
        }
    }
}

/**
 * 地址输入区。
 *
 * 有错误提示时**同时**保留输入框内容：用户多半是少打了个字符，
 * 清空重填比就地修改更麻烦。
 */
@Composable
private fun AddressField(
    address: String,
    errorMessage: String?,
    onAddressChange: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "服务器地址", color = colors.textFaint, fontSize = metrics.fontCaption)
        Spacer(modifier = Modifier.height(metrics.spacingXs))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(metrics.radiusPill))
                .background(colors.container)
                .padding(horizontal = metrics.spacingMd, vertical = metrics.spacingSm),
        ) {
            if (address.isEmpty()) {
                Text(
                    text = "http://服务器地址:8081",
                    color = colors.textGhost,
                    fontSize = metrics.fontSubtitle,
                )
            }
            BasicTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = metrics.fontSubtitle),
                cursorBrush = SolidColor(colors.primary),
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(metrics.spacingXs))
            Text(text = errorMessage, color = colors.textSecondary, fontSize = metrics.fontMicro)
        }
    }
}

/**
 * 连接状态卡：把"还没测 / 正在测 / 成功带版本号 / 失败带原因"四种情况说清楚。
 *
 * 成功与失败都不弹窗：设置页是一次性操作，留在页面上的结论比一闪而过的提示更有用。
 */
@Composable
private fun ConnectionStatusCard(state: HomeUiState) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    val (dotColor, message) = when (val testState = state.testState) {
        ConnectionTestState.Idle -> colors.textGhost to IDLE_MESSAGE
        ConnectionTestState.Testing -> colors.primary to TESTING_MESSAGE
        is ConnectionTestState.Success -> colors.primary to "连接成功 · 服务端版本 ${testState.version}"
        is ConnectionTestState.Failure -> colors.textSecondary to "连接失败：${testState.message}"
    }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(metrics.sizeDot)
                    .clip(RoundedCornerShape(metrics.radiusPill))
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(metrics.spacingSm))
            Text(text = message, color = colors.textSecondary, fontSize = metrics.fontSubtitle)
        }
    }
}

// 底部说明：写清"地址只到端口，不要带页面路径以外的查询串"，并说明保存后的行为。
@Composable
private fun SettingsHint() {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = "保存后会立即用新地址重新加载分类，不需要重启应用。地址只填到端口即可；" +
            "若服务端部署在子路径下（例如 /lumigather），把子路径一起写上。",
        color = colors.textFaint,
        fontSize = metrics.fontMicro,
    )
}

private const val IDLE_MESSAGE = "填好地址后可以先点「测试连接」确认能通"
private const val TESTING_MESSAGE = "正在连接服务器…"
