package theme

import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit

/**
 * 尺寸的语义层：组件只从这里取值，不再出现裸字面量。
 *
 * 为什么还要一层（Tokens 已有原始值）：原始值是「8 / 16 / 20」这种数量，
 * 而组件关心的是「组件内边距 / 区块间距 / 卡片圆角」这种角色。中间这层让
 * 「同一角色统一调整」成为可能——例如觉得卡片圆角太大，只改 radiusMd 的映射，
 * 全工程卡片一起变，不会漏掉某个页面手写的 16.dp。
 *
 * @property spacingXs 组件内边距（最小档）
 * @property spacingSm 组件间距
 * @property spacingMd 区块间距（下限）
 * @property spacingLg 区块间距（上限）/ 页面左右留白
 * @property radiusXs 小标签（徽标、色点框）
 * @property radiusSm 小控件（图标按钮内衬、缩略图）
 * @property radiusMd 卡片、列表项选中态
 * @property radiusLg 大卡片、底部浮动栏
 * @property radiusPill 胶囊按钮、筛选 chip、圆形按钮
 * @property fontMicro 极小注释（不超过一行的小字）
 * @property fontCaption 辅助说明
 * @property fontSubtitle 次要文字、列表副标题
 * @property fontBody 正文基准
 * @property fontTitle 列表项标题、区块标题
 * @property fontDisplay 页面大标题
 * @property sizeIconButton 圆形图标按钮边长
 * @property heightPillCompact 胶囊按钮紧凑高度（列表内操作）
 * @property heightPillRegular 胶囊按钮常规高度（页面内主操作）
 * @property heightPillLarge 胶囊按钮大高度（首屏唯一主按钮）
 * @property thicknessRule 分隔线与描边厚度
 * @property sizeDot 选中态右侧圆点直径
 * @property elevationResting 静止元素投影高度（卡片、圆形按钮）
 * @property elevationRaised 浮起面板投影高度（底部浮层、侧栏）
 */
data class Metrics(
    val spacingXs: Dp = RawSpacing.xs,
    val spacingSm: Dp = RawSpacing.sm,
    val spacingMd: Dp = RawSpacing.md,
    val spacingLg: Dp = RawSpacing.lg,
    val radiusXs: Dp = RawRadius.label,
    val radiusSm: Dp = RawRadius.control,
    val radiusMd: Dp = RawRadius.card,
    val radiusLg: Dp = RawRadius.panel,
    val radiusPill: Dp = RawRadius.pill,
    val fontMicro: TextUnit = RawFontSize.micro,
    val fontCaption: TextUnit = RawFontSize.caption,
    val fontSubtitle: TextUnit = RawFontSize.subtitle,
    val fontBody: TextUnit = RawFontSize.body,
    val fontTitle: TextUnit = RawFontSize.title,
    val fontDisplay: TextUnit = RawFontSize.display,
    val sizeIconButton: Dp = RawSize.iconButton,
    val heightPillCompact: Dp = RawSize.pillCompact,
    val heightPillRegular: Dp = RawSize.pillRegular,
    val heightPillLarge: Dp = RawSize.pillLarge,
    val thicknessRule: Dp = RawSize.hairline,
    val sizeDot: Dp = RawSize.selectedDot,
    val elevationResting: Dp = RawSize.elevationResting,
    val elevationRaised: Dp = RawSize.elevationRaised,
) {
    companion object {
        /**
         * 目前间距/圆角/字号不随主题变化（暗色只换色不换尺寸），
         * 仍然走 CompositionLocal：将来需要「大字模式」时只换这个实例即可，组件不用改。
         */
        val Default = Metrics()
    }
}
