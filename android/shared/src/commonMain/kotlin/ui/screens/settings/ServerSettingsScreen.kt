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
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.HomeLayout
import ui.screens.state.ConnectionTestState
import ui.screens.state.HomeUiState
import ui.screens.state.UpdateUiState

@Composable
fun ServerSettingsScreen(
    state: HomeUiState,
    onAddressChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
    // 应用更新（走 GitHub Releases，不依赖登录态与服务器地址）
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
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

        // 账号块放在连接状态之后、动作按钮之前：先确认"连得上"，再谈"登录谁"
        item { AccountCard(state = state, onLogin = onLogin, onLogout = onLogout) }

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

        // 更新区放在最后：它是最不紧急的一块，但"连服务器"这个问题解决不了时，
        // 用户很可能就是需要换一个更新的版本再来试
        item {
            UpdateCard(
                state = state,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onCancelUpdate = onCancelUpdate,
            )
        }
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

/**
 * 账号块：登录入口与登录态摘要。
 *
 * 未登录只给「登录」（点开弹层）；已登录显示有效期摘要 + 「退出登录」。
 * 有效期写"30 天内有效 / 3 天内有效"而不是精确时刻：那个时刻是客户端按服务端 TTL 推算的，
 * 显示成精确时间会让人以为它是权威值（真正的判定在服务端）。
 *
 * "服务端未设置密码"的提示放在这里而不是弹层里：登录弹层关掉就看不到了，
 * 而这条提示是"安全性建议"，应该在设置页一直留着。
 */
@Composable
private fun AccountCard(
    state: HomeUiState,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    AppCard {
        Text(text = "账号", color = colors.textFaint, fontSize = metrics.fontCaption)
        Spacer(modifier = Modifier.height(metrics.spacingXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.authSummary,
                color = colors.textPrimary,
                fontSize = metrics.fontSubtitle,
                modifier = Modifier.weight(1f),
            )
            if (state.auth.loggedIn) {
                AppPillButton(
                    text = "退出登录",
                    onClick = onLogout,
                    variant = PillVariant.Secondary,
                    size = PillSize.Compact,
                )
            } else {
                AppPillButton(
                    text = "登录",
                    onClick = onLogin,
                    size = PillSize.Compact,
                )
            }
        }
        if (state.auth.noPassword) {
            Spacer(modifier = Modifier.height(metrics.spacingXs))
            Text(
                text = "这台服务器还没有设置访问密码，任何人都能直接进入，建议尽快在服务端设置",
                color = colors.textFaint,
                fontSize = metrics.fontMicro,
            )
        }
    }
}

/**
 * 应用更新区。
 *
 * 位置与依赖都很克制：它不读服务器地址、不读登录态，因为「连接服务器」页在**还没连上**时就要显示，
 * 而更新包来自 GitHub —— 连不连得上自家服务端与能不能升级是两件事。
 *
 * 失败一律内联显示在本卡片里（不写全局那一行提示槽）：检查更新是次要动作，
 * 它失败绝不能盖掉「登录已过期」「保存失败」这类与用户当前操作直接相关的提示。
 */
@Composable
private fun UpdateCard(
    state: HomeUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val currentVersion = state.localVersionName.ifEmpty { "未知" }

    AppCard {
        Text(text = "应用更新", color = colors.textFaint, fontSize = metrics.fontCaption)
        Spacer(Modifier.fillMaxWidth().height(metrics.spacingXs))
        Text(
            text = "当前版本 $currentVersion",
            color = colors.textSecondary,
            fontSize = metrics.fontMicro,
        )
        Spacer(Modifier.fillMaxWidth().height(metrics.spacingSm))
        UpdateStatusBlock(
            update = state.update,
            currentVersion = currentVersion,
            onCheckUpdate = onCheckUpdate,
            onDownloadUpdate = onDownloadUpdate,
            onCancelUpdate = onCancelUpdate,
        )
    }
}

// 状态分发：每种状态一块小组件，避免一个函数里塞八种分支
@Composable
private fun UpdateStatusBlock(
    update: UpdateUiState,
    currentVersion: String,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    when (update) {
        UpdateUiState.Idle -> AppPillButton(
            text = "检查更新",
            onClick = onCheckUpdate,
            variant = PillVariant.Secondary,
        )

        UpdateUiState.Checking -> AppPillButton(
            text = "检查中…",
            onClick = onCheckUpdate,
            variant = PillVariant.Secondary,
            enabled = false,
        )

        is UpdateUiState.Available -> UpdateAvailableBlock(update, onDownloadUpdate)
        is UpdateUiState.Downloading -> UpdateDownloadingBlock(update, onCancelUpdate)
        UpdateUiState.Latest -> UpdateStatusText("已是最新版本 $currentVersion")
        UpdateUiState.InstallStarted -> UpdateStatusText("已调起系统安装界面，请在系统提示里确认安装")
        is UpdateUiState.NeedPermission -> UpdatePermissionBlock(update.appLabel, onDownloadUpdate)
        is UpdateUiState.Failed -> UpdateFailedBlock(update.message, onCheckUpdate)
    }
}

// 有新版本：版本号 + 体积 + 更新说明 + 下载按钮
@Composable
private fun UpdateAvailableBlock(update: UpdateUiState.Available, onDownloadUpdate: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val release = update.release

    Text(
        text = "新版本 ${release.versionName}",
        color = colors.textPrimary,
        fontSize = metrics.fontSubtitle,
    )
    if (release.sizeBytes > 0) {
        Text(
            text = "体积 ${formatBytes(release.sizeBytes)}",
            color = colors.textFaint,
            fontSize = metrics.fontMicro,
        )
    }
    if (release.releaseNotes.isNotEmpty()) {
        Spacer(Modifier.fillMaxWidth().height(metrics.spacingXs))
        Text(
            text = release.releaseNotes,
            color = colors.textSecondary,
            fontSize = metrics.fontMicro,
        )
    }
    Spacer(Modifier.fillMaxWidth().height(metrics.spacingSm))
    AppPillButton(text = "下载并安装", onClick = onDownloadUpdate)
}

// 下载中：进度文案 + 取消（取消回到可再次下载，不报错）
@Composable
private fun UpdateDownloadingBlock(
    update: UpdateUiState.Downloading,
    onCancelUpdate: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = "正在下载 ${formatProgress(update.downloadedBytes, update.totalBytes)}",
        color = colors.textPrimary,
        fontSize = metrics.fontCaption,
    )
    Spacer(Modifier.fillMaxWidth().height(metrics.spacingSm))
    AppPillButton(text = "取消", onClick = onCancelUpdate, variant = PillVariant.Ghost)
}

// 需授权：把"接下来做什么"写清楚，用户才知道去系统设置里开哪一项
@Composable
private fun UpdatePermissionBlock(appLabel: String, onDownloadUpdate: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(
        text = "请先允许「$appLabel」安装未知应用，然后回到这里重新点一次「下载并安装」",
        color = colors.textPrimary,
        fontSize = metrics.fontCaption,
    )
    Spacer(Modifier.fillMaxWidth().height(metrics.spacingSm))
    AppPillButton(text = "重新下载并安装", onClick = onDownloadUpdate)
}

// 失败：内联原因 + 重试（不弹 toast、不占用全局提示槽）
@Composable
private fun UpdateFailedBlock(message: String, onCheckUpdate: () -> Unit) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(text = message, color = colors.textPrimary, fontSize = metrics.fontCaption)
    Spacer(Modifier.fillMaxWidth().height(metrics.spacingSm))
    AppPillButton(text = "重试", onClick = onCheckUpdate, variant = PillVariant.Secondary)
}

// 纯文本状态（已最新 / 已调起安装）共用一行
@Composable
private fun UpdateStatusText(text: String) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics

    Text(text = text, color = colors.textSecondary, fontSize = metrics.fontCaption)
}

// 下载进度文案：总大小未知时只报已下载，不显示百分比（避免出现 3% 才发现总量是猜的）
private fun formatProgress(downloadedBytes: Long, totalBytes: Long): String {
    if (totalBytes <= 0L) return formatBytes(downloadedBytes)
    val percent = (downloadedBytes * PERCENT_SCALE / totalBytes).toInt()
    return "$percent%（${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}）"
}

// 字节数转可读文本：更新包是 MB 级，KB/MB 两档足够
private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "未知大小"
    val kilobytes = bytes / BYTES_PER_KB
    if (kilobytes < BYTES_PER_KB) return "${bytes / BYTES_PER_KB} KB"
    val tenthsOfMegabyte = bytes * TEN / (BYTES_PER_KB * BYTES_PER_KB)
    return "${tenthsOfMegabyte / TEN}.${tenthsOfMegabyte % TEN} MB"
}

private const val PERCENT_SCALE = 100L
private const val BYTES_PER_KB = 1024L
private const val TEN = 10L
