// 服务器地址的持久化入口（声明）。
//
// 为什么是接口 + expect 工厂：commonMain 看不到 Android 的 SharedPreferences，
// 而页面（commonMain）必须在启动时同步读一次地址来决定"进设置态还是进主界面"。
// 同步读这件事排除了异步的存储模块方案，所以走 SharedPreferences，平台差异用 expect/actual 包住。
//
// 契约：read() 返回空串表示"从未配置"，调用方据此进入设置态 —— 不提供任何默认地址兜底，
// 否则用户永远不知道自己没配过。

package ui.screens.settings

interface ServerAddressStore {
    fun read(): String
    fun write(address: String)
}

// 平台实现由 androidMain 提供（SharedPreferences）。
expect fun createServerAddressStore(): ServerAddressStore
