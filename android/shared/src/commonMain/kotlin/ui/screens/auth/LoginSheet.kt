// 登录弹层：单密码字段 + 记住此设备 + 内联错误。
//
// 为什么用 ModalBottomSheet 而不是全屏页：登录是"顺手做一下"的动作，弹层不打断当前的浏览位置；
// 服务器地址另有一个独立设置页，所以这里**只放密码**，不放地址（两处都能改地址只会互相打架）。
//
// 键盘细节（都是踩过坑的成熟做法）：
// - visualTransformation 遮挡输入；
// - keyboardType = Password 让输入法切到密码键盘（同时禁用联想与学习）；
// - autoCorrectEnabled = false 显式关掉自动纠错 —— 密码被"纠正"一个字是最恶心的体验；
// - imeAction = Done + onDone 提交，让用户输完直接回车，不用去够按钮。

package ui.screens.auth

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Checkbox
import com.tencent.kuikly.compose.material3.ModalBottomSheet
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.PasswordVisualTransformation
import theme.AppTheme
import ui.components.AppPillButton
import ui.components.PillSize
import ui.components.PillVariant
import ui.screens.state.LoginUiState

@Composable
fun LoginSheet(
    visible: Boolean,
    password: String,
    rememberDevice: Boolean,
    loginState: LoginUiState,
    canSubmit: Boolean,
    onPasswordChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    noPasswordServer: Boolean = false,
) {
    if (!visible) return

    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val isSubmitting = loginState is LoginUiState.Submitting

    ModalBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.spacingLg)
                // 底部留白：没有它按钮会贴着屏幕边缘，手势条区域很难点准
                .padding(bottom = metrics.spacingLg),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingMd),
        ) {
            Text(text = "登录", color = colors.textPrimary, fontSize = metrics.fontTitle)
            Text(
                text = "登录后可以收藏图片",
                color = colors.textFaint,
                fontSize = metrics.fontCaption,
            )

            PasswordField(
                password = password,
                enabled = !isSubmitting,
                noPasswordServer = noPasswordServer,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
            )

            // 服务器没设密码时明确告诉用户：此时空密码也能登进去，别让人对着输入框猜该填什么
            if (noPasswordServer) {
                Text(
                    text = "这台服务器还没有设置访问密码，可以直接点「登录」",
                    color = colors.textFaint,
                    fontSize = metrics.fontMicro,
                )
            }

            // 内联错误：贴在表单里、不用 toast。文案是后端原文（如「密码错误」），
            // 用户要看到的是"为什么不行"，而不是笼统的"登录失败"。
            val failure = loginState as? LoginUiState.Failed
            if (failure != null) {
                // 设计规范的色板里没有红系语义色，这里用「⚠ + 主文字色」表达错误：
                // 在没有新色值的前提下，这是对比度最高而不会看错的写法（将来若补 error 色，这里一处替换）。
                Text(
                    text = "⚠ ${failure.message}",
                    color = colors.textPrimary,
                    fontSize = metrics.fontMicro,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberDevice, onCheckedChange = onRememberChange)
                Spacer(modifier = Modifier.width(metrics.spacingXs))
                Text(
                    text = "记住此设备（30 天）",
                    color = colors.textSecondary,
                    fontSize = metrics.fontCaption,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(metrics.spacingSm)) {
                AppPillButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = PillVariant.Secondary,
                    size = PillSize.Compact,
                )
                AppPillButton(
                    text = if (isSubmitting) "登录中…" else "登录",
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                    variant = PillVariant.Primary,
                    size = PillSize.Compact,
                    // canSubmit 已包含"冷却中 / 提交中 / 密码为空"三个条件，
                    // 所以失败后的 1.5 秒里按钮是灰的，用户点不出第二个请求
                    enabled = canSubmit,
                )
            }
        }
    }
}

// 密码输入框：容器底 + 全圆角，与设置页的地址输入框同一套视觉。
@Composable
private fun PasswordField(
    password: String,
    enabled: Boolean,
    noPasswordServer: Boolean,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = AppTheme.colors
    val metrics = AppTheme.metrics
    val placeholder = if (noPasswordServer) "（未设密码，可留空）" else "访问密码"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(colors.container)
            .padding(horizontal = metrics.spacingMd, vertical = metrics.spacingSm),
    ) {
        if (password.isEmpty()) {
            Text(
                text = placeholder,
                color = colors.textGhost,
                fontSize = metrics.fontSubtitle,
            )
        }
        BasicTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = metrics.fontSubtitle),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
    }
}
