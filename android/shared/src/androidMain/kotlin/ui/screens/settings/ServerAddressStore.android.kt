// 服务器地址持久化的 Android 实现（SharedPreferences）。
//
// 键名与文件名都收成常量：它们一旦定下来就有"旧安装升级后还读得到"的兼容含义，
// 散落成字面量最容易在重构时被顺手改掉，导致老用户配置凭空消失。
//
// 版本后缀 _v1：将来要换存储结构（比如从明文换加密）时，可以并存读旧键、迁移后写新键，
// 而不是直接把老用户的地址丢掉。

package ui.screens.settings

import android.content.Context

// 配置文件与键名。改这两个字符串等于让已安装用户的地址失效，改动前必须想清楚迁移。
private const val PREFERENCES_NAME = "lumigather_settings_v1"
private const val KEY_SERVER_ADDRESS = "server_base_url"

/**
 * 宿主上下文持有者。
 *
 * 为什么需要它：页面由 Kuikly 按 pageName 创建，构造期拿不到 Android Context；
 * 而 SharedPreferences 必须要一个 Context。宿主（MainActivity）在 onCreate 里装一次即可。
 *
 * 装不上时的降级：返回 null，store 退化成"内存存储 + 未配置"，
 * 结果是用户会看到设置界面（而不是崩溃）。这是有意的：配置系统出错不该让 App 起不来。
 */
object AndroidHostContext {
    private var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    internal fun current(): Context? = applicationContext
}

private class SharedPreferencesServerAddressStore(
    private val context: Context,
) : ServerAddressStore {

    private val preferences
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String = preferences.getString(KEY_SERVER_ADDRESS, "").orEmpty()

    // 用 commit 而不是 apply：这是用户点一次"保存"的一次性动作，主线程阻塞一两毫秒无所谓；
    // 换来的是"保存完立刻杀进程，重开地址还在"这个确定性 —— apply 的异步落盘在极端情况下
    // 会让"刚配置完就黑屏"的用户丢掉刚填的地址，而配置丢失比多等两毫秒难受得多。
    override fun write(address: String) {
        preferences.edit().putString(KEY_SERVER_ADDRESS, address).commit()
    }
}

// 宿主没装上下文时的兜底实现：值只活在内存里，重启即忘（表现就是"又要重新填一次"）。
// 它存在只是为了不崩，正常路径永远走 SharedPreferences。
private object VolatileServerAddressStore : ServerAddressStore {
    private var address: String = ""

    override fun read(): String = address

    override fun write(address: String) {
        this.address = address
    }
}

actual fun createServerAddressStore(): ServerAddressStore {
    val context = AndroidHostContext.current() ?: return VolatileServerAddressStore
    return SharedPreferencesServerAddressStore(context)
}
