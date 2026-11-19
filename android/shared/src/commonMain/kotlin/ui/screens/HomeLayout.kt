// 页面级布局常量：三栏骨架里所有「有名字但没有语义槽位」的尺寸集中在这里。
//
// 为什么要单独一个文件：T2 的 Metrics 覆盖的是「组件与区块」的通用尺寸（间距/圆角/字号/控件高度），
// 而三栏骨架需要的几个数是**页面独有**的（侧栏多宽、主界面缩到多少、遮罩多深、模糊半径多大）。
// 它们既不能塞进 Metrics（那层是通用语义，不该知道"侧栏"这种东西），也不该散落在各页面里
// ——散落之后就会变成「左栏 286、右栏 300」这种没人敢动的漂移。
//
// 唯一例外说明：sidebarWidth 的 286 与主界面 0.94 都来自界面规范给定的交互参数，
// 不是这里拍脑袋定的；将来要调只改这两处，页面代码不用动。

package ui.screens

import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

object HomeLayout {

    // 内侧栏宽度。规范给定的位移量：主界面右移的距离与侧栏宽度一致，视觉上才像"被推开"。
    val sidebarWidth: Dp = 286.dp

    // 侧栏打开时主界面的缩放比例（规范：缩到 94%）。
    const val mainScaleWhenOpen: Float = 0.94f

    // 主界面缩小的幅度，由上面那个比例推出，避免页面里再手算一次 1 - 0.94。
    const val mainScaleDelta: Float = 1f - mainScaleWhenOpen

    // 遮罩最大不透明度。取 0.35：能压暗背景、又不至于让用户看不清"主界面还在那儿"。
    const val scrimMaxAlpha: Float = 0.35f

    // 侧栏打开时的背景模糊半径。轻微即可，过大会让整屏变糊、掉帧明显。
    val scrimBlurRadius: Dp = 8.dp

    // 网格列数。手机竖屏三列是「一眼能看清缩略图 + 不必过度滑动」的折中。
    const val gridColumns: Int = 3

    // 起手判定区宽度：手指落在这条带内才算「边缘滑动」。
    // 从 20dp 提到 64dp 的原因：一条 20dp 的缝要求手指精确落点，真机上成功率很低；
    // 而且手势竞技场在手势开始就定胜负，事后补救无效 —— 所以起手区必须够宽，
    // 但又不能占满全屏（否则会和网格的横向手势抢）。64dp 约等于拇指宽度。
    val edgeSwipeWidth: Dp = 64.dp

    // 横向拖动累计超过这个距离才真正开栏，避免指尖轻微抖动就误开。
    val edgeDragThreshold: Dp = 24.dp

    // 右侧详情栏里大图预览的高度。给固定高度而不是比例：栏内还有信息区要占位。
    val previewHeight: Dp = 220.dp

    // 网格四周留白不在这里定义：它等于 metrics.spacingLg（16），
    // 复用一个已有语义比再声明一个同值常量更不容易漂移。

    // 图标按钮里用的字形。没有图标资源，用等宽符号代替，避免为几个图标引入图标库。
    const val iconMenu: String = "☰"
    const val iconRefresh: String = "⟳"
    const val iconSettings: String = "⚙"
    const val iconClose: String = "✕"
    const val iconServer: String = "🖥"
    const val iconSearch: String = "🔍"
}
