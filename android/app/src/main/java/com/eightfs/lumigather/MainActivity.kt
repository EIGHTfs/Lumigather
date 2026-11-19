// 安卓端唯一入口 Activity：只做"搭壳 + 装上下文 + 注册适配器 + 打开 Kuikly 页面"四件事。
//
// 界面全在 Kuikly 侧（shared 的 ui/screens）。这里不提供任何业务配置：
// 服务端地址由用户在 App 内填写并持久化，宿主不替用户决定，也不再保留兜底地址。
//
// 四步的顺序都是硬要求（顺序错了会静默失败，编译期看不出来）：
// 1) 装应用上下文 —— 页面要读写 SharedPreferences 才能拿到服务器地址；
// 2) 注册图片适配器 —— 不注册则所有图片永远停在占位态；
// 3) 把容器挂进窗口 —— 渲染视图要等容器有了大小才初始化；
// 4) 用 delegator 打开页面 —— 页面由 KSP 生成的注册表按 pageName 创建。

package com.eightfs.lumigather

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import ui.screens.HOME_PAGE_NAME
import ui.screens.settings.AndroidHostContext

// JVM 执行模式下 contextCode 传空串（Kuikly 官方的约定：代码在本地 Kotlin 侧，不动态下发）。
private const val CONTEXT_CODE_JVM = ""

class MainActivity : AppCompatActivity() {

    private lateinit var pageDelegator: KuiklyRenderViewBaseDelegator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1) 上下文：页面侧的 SharedPreferences 存储靠它拿到 Context
        AndroidHostContext.install(this)
        // 2) 图片适配器：漏了这步图片全白，是最容易误判成"网络不通"的静默失败
        registerImageAdapter()

        val container = FrameLayout(this)
        setContentView(container)

        pageDelegator = KuiklyRenderViewBaseDelegator(HostDelegate)
        pageDelegator.onAttach(
            container,
            CONTEXT_CODE_JVM,
            HOME_PAGE_NAME,
            // 页面启动参数留空：地址来自用户配置，不再由宿主注入
            emptyMap(),
        )
    }

    // 生命周期要转给 Kuikly，否则页面内的协程/定时器不会随前后台暂停，
    // 退出后容器里的渲染视图也不会释放。
    override fun onResume() {
        super.onResume()
        pageDelegator.onResume()
    }

    override fun onPause() {
        super.onPause()
        pageDelegator.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pageDelegator.onDetach()
    }

    /**
     * 返回键：先问 Kuikly 页面，页面不要才退出应用。
     *
     * 页面侧只在"有东西可收"时才注册返回回调（侧栏开着 / 停在设置界面且有旧配置），
     * Kuikly 依据"回调列表是否为空"回报 consumed：
     * - consumed=true  → 页面已处理（收侧栏 / 退出设置），这里什么都不做；
     * - consumed=false → 页面没处理，交给系统退出。
     * 所以页面不注册回调时，返回键行为与没接这套逻辑时完全一致。
     */
    @Deprecated("AppCompatActivity 上仍走 onBackPressed，等升级到 OnBackPressedDispatcher 再替换")
    override fun onBackPressed() {
        if (!pageDelegator.onBackPressed()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * 注册渲染层需要的适配器。
     *
     * `== null` 判断来自官方 demo：宿主可能有多个入口，重复覆盖会打断正在加载的图片
     * （旧适配器的线程池与缓存被丢弃，正在加载的格子会拿到空结果）。
     */
    private fun registerImageAdapter() {
        if (KuiklyRenderAdapterManager.krImageAdapter == null) {
            KuiklyRenderAdapterManager.krImageAdapter = LumigatherImageAdapter(applicationContext)
        }
    }

    // 宿主委托：所有回调都用接口默认实现。
    // 显式写一个对象而不是传 null，是为了将来接"页面加载完成/异常"回调时只改这里。
    private object HostDelegate : KuiklyRenderViewBaseDelegatorDelegate
}
