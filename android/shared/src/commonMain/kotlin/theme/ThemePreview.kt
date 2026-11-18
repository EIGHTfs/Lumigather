package theme

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.TextUnit

/**
 * 主题自检屏：把当前主题的每个语义色与每档字号排成一屏，肉眼核对用。
 *
 * 为什么要它：配色和字号的问题（对比不足、层级看不出、暗色下文字发灰）在代码里读不出来，
 * 只有真机/预览器上并排看才能发现。这个函数不参与业务页面，只被调试入口调用。
 *
 * 注意它只依赖语义名——如果哪天需要在这里引用 mist/ink 这种色名，说明语义层缺字段了。
 */
@Composable
fun ThemePreview(
    mode: ThemeMode = ThemeMode.Light,
    modifier: Modifier = Modifier,
) {
    LumigatherTheme(mode = mode) {
        val colors = AppTheme.colors
        val metrics = AppTheme.metrics

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(metrics.spacingMd),
        ) {
            Text(
                text = "主题自检 · ${if (mode == ThemeMode.Dark) "暗色" else "浅色"}",
                color = colors.textPrimary,
                fontSize = metrics.fontTitle,
            )

            Spacer(modifier = Modifier.height(metrics.spacingMd))

            // 每行一个语义色：左色块 + 右字段名，方便逐个对照设计规范
            colorSamples(colors).forEach { (name, swatch) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = metrics.spacingXs)) {
                    Spacer(
                        modifier = Modifier
                            .size(metrics.spacingLg)
                            .clip(RoundedCornerShape(metrics.radiusSm))
                            .background(swatch),
                    )
                    Spacer(modifier = Modifier.width(metrics.spacingSm))
                    Text(
                        text = name,
                        color = colors.textSecondary,
                        fontSize = metrics.fontSubtitle,
                    )
                }
            }

            Spacer(modifier = Modifier.height(metrics.spacingMd))

            // 每档字号一行，同一句话依次放大，用来判断层级是否够分明
            fontSizeSamples(metrics).forEach { (name, size) ->
                Text(
                    text = "$name · 拾光集 128 张",
                    color = colors.textPrimary,
                    fontSize = size,
                )
                Spacer(modifier = Modifier.height(metrics.spacingXs))
            }
        }
    }
}

/** 语义色清单：字段名即核对项，顺序与 [Colors] 声明一致，漏一个这里就会少一行。 */
private fun colorSamples(colors: Colors): List<Pair<String, Color>> = listOf(
    "background" to colors.background,
    "backgroundAlt" to colors.backgroundAlt,
    "surface" to colors.surface,
    "surfaceHigh" to colors.surfaceHigh,
    "container" to colors.container,
    "textPrimary" to colors.textPrimary,
    "textSecondary" to colors.textSecondary,
    "textFaint" to colors.textFaint,
    "textGhost" to colors.textGhost,
    "primary" to colors.primary,
    "onPrimary" to colors.onPrimary,
    "rule" to colors.rule,
    "shadow" to colors.shadow,
)

/** 字号清单：六档全列，缺档说明 Tokens 与规范 drift。 */
private fun fontSizeSamples(metrics: Metrics): List<Pair<String, TextUnit>> = listOf(
    "fontDisplay" to metrics.fontDisplay,
    "fontTitle" to metrics.fontTitle,
    "fontBody" to metrics.fontBody,
    "fontSubtitle" to metrics.fontSubtitle,
    "fontCaption" to metrics.fontCaption,
    "fontMicro" to metrics.fontMicro,
)
