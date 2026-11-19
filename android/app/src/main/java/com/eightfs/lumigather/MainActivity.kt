// 安卓端唯一入口 Activity：只做"搭壳 + 注册适配器 + 打开 Kuikly 页面"三件事。
//
// 为什么界面全在 Kuikly 侧：业务界面（三栏、网格、详情）都在 shared 的 ui/screens 里，
// 这里只负责提供一个能承载 Kuikly 渲染视图的容器。这样换壳（加启动页、多入口、深链）不必碰业务代码。
//
// 三个必须按顺序做的步骤（顺序错了会静默失败，编译期看不出来）：
// 1) 注册图片适配器 —— 不注册则所有图片永远停在占位态；
// 2) 先把容器挂进窗口 —— 渲染视图要等容器有了大小才初始化；
// 3) 用 delegator 打开页面 —— 页面由 KSP 生成的注册表按 pageName 创建。

package com.eightfs.lumigather

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import ui.screens.HOME_PAGE_NAME

// 服务端地址。放在宿主而不是页面层：换机器/换端口只改这里一处；
// 将来做"服务器设置页"时，这里会变成从持久化配置读取的入口。
private const val SERVER_BASE_URL = "http://10.10.31.59:8081"

// 传给页面的数据键名，需与页面侧读取的键一致。
private const val PAGE_DATA_BASE_URL = "baseUrl"

// JVM 执行模式下 contextCode 传空串（Kuikly 官方的约定：代码在本地 Kotlin 侧，不动态下发）。
private const val CONTEXT_CODE_JVM = ""

class MainActivity : AppCompatActivity() {

    private lateinit var pageDelegator: KuiklyRenderViewBaseDelegator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerImageAdapter()

        val container = FrameLayout(this)
        setContentView(container)

        pageDelegator = KuiklyRenderViewBaseDelegator(HostDelegate)
        pageDelegator.onAttach(
            container,
            CONTEXT_CODE_JVM,
            HOME_PAGE_NAME,
            createPageData(),
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

    // 先让 Kuikly 页面处理返回键（例如收起侧栏），它不处理才交回系统退出。
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
     * 判断里带 == null 是官方 demo 的写法：宿主可能有多个入口，重复覆盖会打断正在加载的图片
     * （旧适配器的线程池与缓存被丢弃，正在加载的格子会拿到空结果）。
     */
    private fun registerImageAdapter() {
        if (KuiklyRenderAdapterManager.krImageAdapter == null) {
            KuiklyRenderAdapterManager.krImageAdapter = LumigatherImageAdapter(applicationContext)
        }
    }

    // 页面需要的启动参数。目前只有服务端地址，后续加"主题模式""默认分类"也走这里，
    // 不在页面里写死。
    private fun createPageData(): Map<String, Any> = mapOf(PAGE_DATA_BASE_URL to SERVER_BASE_URL)

    // 宿主委托：所有回调都用接口默认实现。
    // 显式写一个对象而不是传 null，是为了将来要接"页面加载完成/异常"回调时只改这里。
    private object HostDelegate : KuiklyRenderViewBaseDelegatorDelegate
}
