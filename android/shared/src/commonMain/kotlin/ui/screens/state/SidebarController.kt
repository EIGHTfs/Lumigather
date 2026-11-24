// 侧栏子状态机：左右侧栏的开合、当前选中的图片、左栏搜索词。
//
// 它是这次拆分里最简单的一块（没有网络、没有异步），先拆它能把"状态通道 + 显式回调"
// 这套模式跑通：后面的下载、设置、登录都照同一形状搬。
//
// 唯一的外部依赖是回调 [onDetailOpened]：用户从网格点开一张图时，详情区的下载结果
// 必须一并清掉（否则新图会顶着上一张的"已保存到相册"），但下载状态不归这里管，
// 所以只能"通知"，不能伸手去改。

package ui.screens.state

import com.eightfs.lumigather.data.ImageItem

class SidebarController(
    private val channel: StateChannel,
    private val onDetailOpened: () -> Unit = {},
) {

    fun openLeftSidebar() {
        channel.mutate { it.copy(leftSidebarOpen = true, rightSidebarOpen = false) }
    }

    // 手势开右栏：不指定图片。右栏自己会处理 selectedImage == null 的空态，
    // 所以从右侧边缘滑出时看到的是「提示去点图片」而不是空白面板。
    fun openRightSidebar() {
        channel.mutate { it.copy(rightSidebarOpen = true, leftSidebarOpen = false) }
    }

    fun openDetail(image: ImageItem) {
        channel.mutate {
            it.copy(
                rightSidebarOpen = true,
                selectedImage = image,
                leftSidebarOpen = false,
            )
        }
        // 通知出去：让下载子状态机把上一张的结果清掉
        onDetailOpened()
    }

    fun closeSidebars() {
        channel.mutate { it.copy(leftSidebarOpen = false, rightSidebarOpen = false) }
    }

    // 只收左栏：用户又点了一次"当前分类"，只是想关掉分类列表，右侧详情不该跟着消失
    fun closeLeftSidebar() {
        channel.mutate { it.copy(leftSidebarOpen = false) }
    }

    // 只清选中图片、不动开合状态：换服务器时旧图必须失效，但侧栏的开合不该被这次清理顺手改掉
    fun clearSelectedImage() {
        channel.mutate { it.copy(selectedImage = null) }
    }

    /**
     * 切换分类时由浏览子状态机间接触发：关掉两侧栏并清掉选中项。
     *
     * 保留成"一次写三条字段"而不是拆成三次调用，是为了和拆分前的行为逐字一致
     * （中间不留"侧栏已关但旧图还在高亮"的中间态）。
     */
    fun closeAndClearSelection() {
        channel.mutate {
            it.copy(leftSidebarOpen = false, rightSidebarOpen = false, selectedImage = null)
        }
    }

    fun updateSearchQuery(query: String) {
        channel.mutate { it.copy(searchQuery = query) }
    }

    // 换服务器后连搜索词一起清：它属于"上一台服务器的浏览痕迹"
    fun resetSearch() {
        channel.mutate { it.copy(searchQuery = "") }
    }
}
