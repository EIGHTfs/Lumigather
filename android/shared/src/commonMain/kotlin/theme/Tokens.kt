package theme

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

/**
 * 原始令牌层：只回答「这个值是多少」，不回答「它用在哪里」。
 *
 * 整层都是 internal：不进入本库对外公开的类型，只有 theme 包内的 Colors/Metrics 引用它。
 * 客观说明——Kotlin 的 internal 是「模块内可见」，同模块的 ui/ 技术上仍能引用到 RawColors，
 * 那属于违规用法，靠这条约定和代码审查把关；对外（其它模块）确实拿不到。
 *
 * 色值唯一来源：docs/android/安卓端界面设计规范.md 第 1.1 节（数值取自参考项目实际定义，不是目测）。
 * 尺寸唯一来源：同一文档 1.2 / 1.3 / 1.4 节。
 */

// 十六进制字面量在 Kotlin 里默认是 Long，而 Color(Long) 走的是「打包色值」重载，
// 传 Long 会被当成 colorspace 位图解析成错色；必须 .toInt() 命中 Color(Int) 的 ARGB 重载。
internal object RawColors {

    // 背景 8 档：冷调浅蓝灰，从最浅到最深，用来在无边框的前提下靠色块分层
    val mistTop = Color(0xFFEBF1FE.toInt())
    val mistBottom = Color(0xFFF6F7FC.toInt())
    val mist = Color(0xFFF0F4FD.toInt())
    val mistLowest = Color(0xFFFBFCFF.toInt())
    val mistLow = Color(0xFFF6F9FE.toInt())
    val mistContainer = Color(0xFFEEF1F9.toInt())
    val mistHigh = Color(0xFFE7EBF4.toInt())
    val mistHighest = Color(0xFFDFE4EE.toInt())

    // 文字 4 档：规范明确「不要自己加第五级」，层级靠这四档 + 字号共同表达
    val ink = Color(0xFF1E232E.toInt())
    val inkMuted = Color(0xFF4F5562.toInt())
    val inkFaint = Color(0xFF646974.toInt())
    val inkGhost = Color(0xFFA1A5AC.toInt())

    // 分隔与阴影：rule 用于极少数必须分隔处，shadow 是投影底色（透明度由 ShadowAlpha 统一给）
    val ruleLight = Color(0xFFDCE0E8.toInt())
    val shadowInk = Color(0xFF0B152D.toInt())

    // 主色种子：蓝紫，浅色主题直接用，暗色主题提亮后使用
    val accentSeed = Color(0xFF4B67B4.toInt())

    // 暗色（night 夜）三基色：规范只给了顶/底/基准三档，其余层级由这三档混出
    val nightTop = Color(0xFF1D222C.toInt())
    val nightBottom = Color(0xFF1B1C23.toInt())
    val nightBase = Color(0xFF1C1F28.toInt())
}

/**
 * 透明度令牌。阴影、暗色分层、次级文字都靠这些比例表达，
 * 分散写在调用点会出现「同一个层次在不同页面深浅不一」。
 */
internal object RawAlpha {
    /** 浅色投影：足够看出悬浮，又不至于在浅底上发灰 */
    const val ShadowOnLight = 0.12f

    /** 暗色投影：深底上要更重才看得出层次 */
    const val ShadowOnDark = 0.45f

    /** 暗色里「比 surface 高一级」的底色：在 nightTop 上叠一点点最浅色 */
    const val DarkLiftSurface = 0.06f

    /** 暗色里容器的提亮幅度（搜索框、未选中胶囊这一级） */
    const val DarkLiftContainer = 0.10f

    /** 暗色分隔线：比容器更亮一点，才能在深底上看出来 */
    const val DarkLiftRule = 0.16f

    /** 暗色主色提亮比例：种子色直接铺在深底上对比不足 */
    const val DarkLiftPrimary = 0.18f

    /** 暗色文字降级三档的比例下限，依次对应 secondary / faint / ghost */
    const val DarkTextSecondary = 0.22f
    const val DarkTextFaint = 0.40f
    const val DarkTextGhost = 0.58f
}

/** 间距：严格 8pt grid，规范只允许 8 / 12 / 16 / 24 这四个值 */
internal object RawSpacing {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
}

/**
 * 圆角：规范只允许 8 / 10 / 16 / 20 / 全圆。
 * 全圆取 999（而不是 99）：屏幕再高也超不过这个值，效果等价于「高度的一半」，且不用关心控件尺寸。
 */
internal object RawRadius {
    val label: Dp = 8.dp
    val control: Dp = 10.dp
    val card: Dp = 16.dp
    val panel: Dp = 20.dp
    val pill: Dp = 999.dp
}

/** 字号：规范只允许 11 / 12 / 13 / 15 / 18 / 22 */
internal object RawFontSize {
    val micro: TextUnit = 11.sp
    val caption: TextUnit = 12.sp
    val subtitle: TextUnit = 13.sp
    val body: TextUnit = 15.sp
    val title: TextUnit = 18.sp
    val display: TextUnit = 22.sp
}
