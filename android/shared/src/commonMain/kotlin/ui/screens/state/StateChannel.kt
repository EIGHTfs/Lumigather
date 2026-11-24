// 子状态机与"整屏状态"之间的唯一通道。
//
// 为什么用"通道 + 显式回调"而不是让子状态机互相持有引用：一旦 A 持有 B，下一步就会 B 持有 A，
// 最后又长回一个大类，只是把代码换个文件摆着。这里每个子状态机只被允许两件事：
//   1) 读当前快照（state）；
//   2) 写新快照（mutate）。
// 需要跨功能协作时，由 facade 在构造时注入一个回调（例如"地址变了要重载内容"），
// 子状态机自己并不知道是谁在响应 —— 于是依赖方向永远指向外面，不会成环。

package ui.screens.state

interface StateChannel {
    /** 当前整屏状态快照。 */
    val state: HomeUiState

    /**
     * 写状态。实现方（facade）负责把它落到 Compose 的 Snapshot 状态上，
     * 于是所有读 state 的组合都会跟着重画。
     */
    fun mutate(transform: (HomeUiState) -> HomeUiState)
}
