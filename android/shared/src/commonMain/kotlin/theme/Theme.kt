package theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 主题入口：颜色与尺寸都通过 CompositionLocal 向下传递。
 *
 * 为什么用 CompositionLocal 而不是全局单例：同一棵界面里可能同时存在两个主题的片段
 * （例如浅色页面里的深色浮层、预览页并排展示亮暗两版）；全局单例做不到局部覆盖，
 * CompositionLocal 可以只包住需要换肤的那一小块。
 */

/** 主题模式。尺寸不随模式变化，所以只携带颜色。 */
enum class ThemeMode(val colors: Colors) {
    Light(Colors.Light),
    Dark(Colors.Dark),
}

/**
 * 颜色的默认值是浅色：即使调用方忘了包 LumigatherTheme，界面也是可用的浅色，
 * 不会因为「没提供主题」直接取到未初始化值而崩。
 *
 * 这两个 Local 是 internal：它们属于实现细节，外部应该只用 AppTheme 与 LumigatherTheme，
 * 避免有人绕过主题入口直接 produce/consume，出现「改了主题但部分界面不跟着变」。
 */
internal val LocalColors = staticCompositionLocalOf { Colors.Light }

internal val LocalMetrics = staticCompositionLocalOf { Metrics.Default }

/** 组件侧的取用入口：`AppTheme.colors.textPrimary` / `AppTheme.metrics.radiusMd`。 */
object AppTheme {
    val colors: Colors
        @Composable @ReadOnlyComposable get() = LocalColors.current

    val metrics: Metrics
        @Composable @ReadOnlyComposable get() = LocalMetrics.current
}

/**
 * 把颜色与尺寸织入组合树。
 *
 * @param mode 浅色 / 暗色
 * @param content 受主题影响的内容
 */
@Composable
fun LumigatherTheme(
    mode: ThemeMode = ThemeMode.Light,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColors provides mode.colors,
        LocalMetrics provides Metrics.Default,
        content = content,
    )
}
