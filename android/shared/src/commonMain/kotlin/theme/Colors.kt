package theme

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.lerp

/**
 * 语义色：组件层唯一允许引用的颜色接口。
 *
 * 字段名一律是「用途」而不是「颜色」。原因是这层要能整体换肤：暗色主题下
 * textPrimary 会变成浅色，如果字段叫 ink，暗色里就自相矛盾；叫 textPrimary 则永远成立。
 *
 * 新增字段的判据：只有当同一个用途在多个组件里重复出现、且可能整体变化时才加；
 * 单个组件的特殊色应该由该组件用现有语义色 + 透明度组合出来。
 *
 * @property background 页面基准底色
 * @property backgroundAlt 页面次级底色（顶/底渐变、分组间隔这类需要与基准区分的地方）
 * @property surface 卡片底，比背景更亮，让卡片「浮」起来而不靠边框
 * @property surfaceHigh 高一层卡片 / 输入区的底
 * @property container 容器底（搜索框、未选中的胶囊、列表选中态的底衬）
 * @property textPrimary 标题与主文字
 * @property textSecondary 次要文字
 * @property textFaint 辅助说明
 * @property textGhost 占位符与禁用态
 * @property primary 主操作色（选中胶囊、主按钮、强调数字）
 * @property onPrimary 铺在主色上的文字/图标
 * @property rule 极少数必须的分隔线
 * @property shadow 投影底色（透明度已含在内，调用方直接用，不要再自己乘 alpha）
 */
data class Colors(
    val background: Color,
    val backgroundAlt: Color,
    // 页面背景的竖向渐变两端：顶部偏蓝、底部偏白。
    // 单独开两个槽而不是复用 background/backgroundAlt，是因为渐变端点与「卡片放在什么底色上」
    // 是两件事 —— 后者要留出浮起的余地，前者只负责页面顶到底的过渡观感。
    val backgroundGradientTop: Color,
    val backgroundGradientBottom: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val container: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val textGhost: Color,
    val primary: Color,
    val onPrimary: Color,
    val rule: Color,
    val shadow: Color,
) {

    companion object {

        /**
         * 浅色（mist 雾）：
         * - 背景取基准 mist 而不是最浅档，这样卡片（mistLowest）才有「浮上来」的余地
         * - 主色直接用种子色，浅底上对比足够
         */
        val Light = Colors(
            background = RawColors.mist,
            backgroundAlt = RawColors.mistBottom,
            backgroundGradientTop = RawColors.mistTop,
            backgroundGradientBottom = RawColors.mistBottom,
            surface = RawColors.mistLowest,
            surfaceHigh = RawColors.mistLow,
            container = RawColors.mistContainer,
            textPrimary = RawColors.ink,
            textSecondary = RawColors.inkMuted,
            textFaint = RawColors.inkFaint,
            textGhost = RawColors.inkGhost,
            primary = RawColors.accentSeed,
            onPrimary = RawColors.mistLowest,
            rule = RawColors.ruleLight,
            shadow = RawColors.shadowInk.copy(alpha = RawAlpha.ShadowOnLight),
        )

        /**
         * 暗色（night 夜）：
         * - 规范只给了 night 顶/底/基准三档，所以层级不用第三套色值，而是「基色 + 最浅色微叠」
         *   混出来 —— 这样暗色和浅色共用一套原始色板，不会出现两套色值各自漂移
         * - 文字从最浅色往下混，比例写进 RawAlpha，保证四档层次和浅色正好镜像
         * - 主色要提亮：种子色（#4B67B4）直接铺在 #1C1F28 上偏暗，看不清选中态
         * - onPrimary 用 night 基准色：提亮后的主色上需要深字才够对比
         */
        val Dark = Colors(
            background = RawColors.nightBase,
            backgroundAlt = RawColors.nightBottom,
            backgroundGradientTop = RawColors.nightTop,
            backgroundGradientBottom = RawColors.nightBottom,
            surface = RawColors.nightTop,
            surfaceHigh = lerp(RawColors.nightTop, RawColors.mistLowest, RawAlpha.DarkLiftSurface),
            container = lerp(RawColors.nightTop, RawColors.mistLowest, RawAlpha.DarkLiftContainer),
            textPrimary = RawColors.mistLowest,
            textSecondary = lerp(RawColors.mistLowest, RawColors.nightBase, RawAlpha.DarkTextSecondary),
            textFaint = lerp(RawColors.mistLowest, RawColors.nightBase, RawAlpha.DarkTextFaint),
            textGhost = lerp(RawColors.mistLowest, RawColors.nightBase, RawAlpha.DarkTextGhost),
            primary = lerp(RawColors.accentSeed, RawColors.mistLowest, RawAlpha.DarkLiftPrimary),
            onPrimary = RawColors.nightBase,
            rule = lerp(RawColors.nightTop, RawColors.mistLowest, RawAlpha.DarkLiftRule),
            shadow = RawColors.shadowInk.copy(alpha = RawAlpha.ShadowOnDark),
        )
    }
}
