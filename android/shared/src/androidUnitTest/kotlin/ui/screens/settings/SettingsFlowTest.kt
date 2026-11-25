// 设置链路与地址规则的离线测试：T8 里唯一能在没有设备的情况下证明"逻辑对"的部分。
//
// 为什么这些必须测：地址归一化与"未配置就进设置态"这两件事一旦错，
// 表现是"用户填对了却进不去"或"每次都让他重填"，而这两类问题在真机上排查成本极高
// （要反复卸载重装、清数据）。把它们钉成断言，真机只用来验键盘与返回键这类交互。

package ui.screens.settings

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import kotlinx.coroutines.runBlocking
import ui.screens.state.ConnectionTestState
import ui.screens.state.HomeStateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
// 样本服务端地址：用 RFC 2606 保留域名 .test，既不写真实部署地址，也不写真实端口
private const val SAMPLE_BASE_URL = "http://server.test"
// 样本根路径：模拟服务端返回的分类绝对路径。
// 必须是**绝对路径**形态（带前导斜杠）：真实服务端返回的就是 /volume6/... 这类绝对路径，
// 换成相对形态会让"绝对路径"分支测不到（MediaSrc.normalize 的"补斜杠"分支会被当成主路径）。
// 用 /root/xxx 这种明显的虚构绝对路径：既保持线上真实形态，又不含任何本机环境信息。
private const val SAMPLE_ROOT_PATH = "/root/honkai"

// 站点配置最小样本：一个分类 + 它的绝对路径（真实响应里 categories 与 directories 是两张表）
private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root/fallback","imagesPerRow":20,
 "categories":[{"name":"崩坏3","label":"崩坏3","color":"#c084fc"}],
 "directories":[{"path":"$SAMPLE_ROOT_PATH","category":"崩坏3"}]}
"""

private const val STATUS_JSON = """
{"ok":true,"needsSetup":false,"needsAuth":false,"authed":false,"port":9000,"uptimeSec":9,"version":"1.0.3"}
"""

private const val IMAGES_JSON = """
{"path":"$SAMPLE_ROOT_PATH","root":"$SAMPLE_ROOT_PATH","recursive":true,"count":1,"total":1,
 "offset":0,"limit":1,"returned":1,
 "images":[{"name":"a.png","path":"$SAMPLE_ROOT_PATH/a.png","url":"/api/media?file=%2Fa.png",
            "size":"1.0 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

// 记忆版存储：验证"写进去的值"就是断言对象，不必碰真实 SharedPreferences。
private class MemoryAddressStore(private var value: String = "") : ServerAddressStore {
    override fun read(): String = value
    override fun write(address: String) {
        value = address
    }

    // 测试里直接读回，用来断言"持久化下来的到底是什么"
    fun stored(): String = value
}

// 按 URL 路由的假传输层：区分三个接口，顺便记录请求过的地址。
private class RoutingTransport(
    private val siteConfig: String = SITE_CONFIG_JSON,
    private val status: String = STATUS_JSON,
) : HttpTransport {

    val requestedUrls = mutableListOf<String>()

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse {
        requestedUrls.add(url)
        return when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, siteConfig, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, status, "application/json")
            url.endsWith("/api/images") -> HttpRawResponse(200, IMAGES_JSON, "application/json")
            else -> HttpRawResponse(404, "{}", "application/json")
        }
    }
}

class ServerAddressRulesTest {

    @Test
    fun keepsSubPathAndStripsTrailingSlashAndSpaces() {
        assertEquals("$SAMPLE_BASE_URL/lumigather", ServerAddressRules.normalize("  http://server.test/lumigather///  "))
    }

    @Test
    fun lowercasesScheme() {
        // 大小写混写的协议头也要能用，否则用户手打 "HTTP://" 就被判非法
        assertEquals("https://h:9000", ServerAddressRules.normalize("HTTPS://h:9000/"))
    }

    @Test
    fun acceptsAddressWithoutPort() {
        assertEquals("http://nas.lan", ServerAddressRules.normalize("http://nas.lan"))
    }

    @Test
    fun rejectsMissingScheme() {
        // 不补默认协议：补了会掩盖"用户以为自己填的是域名/端口"这类误填
        assertEquals(null, ServerAddressRules.normalize("nas.lan:9000"))
    }

    @Test
    fun rejectsBlankAndSpacesAndHostlessAddress() {
        assertEquals(null, ServerAddressRules.normalize("   "))
        assertEquals(null, ServerAddressRules.normalize("$SAMPLE_BASE_URL/a b"))
        assertEquals(null, ServerAddressRules.normalize("http:///path"))
    }

    @Test
    fun errorMessageDistinguishesEmptyFromInvalid() {
        assertEquals(ServerAddressRules.MESSAGE_EMPTY, ServerAddressRules.errorMessageFor("  "))
        assertEquals(ServerAddressRules.MESSAGE_INVALID, ServerAddressRules.errorMessageFor("ftp://x"))
    }
}

class HomeStateHolderTest {

    private fun holderWith(store: ServerAddressStore, transport: HttpTransport): HomeStateHolder =
        HomeStateHolder(
            clientFactory = { address, _ -> ApiClient(address, transport) },
            addressStore = store,
            // 设置链路不涉及下载，给一个"永不成功"的假实现即可（真被调用说明有 bug）
            imageDownloader = object : ui.screens.download.ImageDownloader {
                override suspend fun saveToGallery(
                    imageUrl: String,
                    fileName: String,
                ): ui.screens.download.DownloadResult =
                    ui.screens.download.DownloadResult.Failure(
                        ui.screens.download.DownloadFailureKind.Unknown,
                        "测试里不应该走到下载",
                    )
            },
            // 设置链路也不碰登录态：给个内存桩即可
            authTokenStore = object : com.eightfs.lumigather.data.AuthTokenStore {
                private var stored: com.eightfs.lumigather.data.StoredAuthToken? = null
                override fun read(): com.eightfs.lumigather.data.StoredAuthToken? = stored
                override fun write(token: String, expiresAtEpochMs: Long) {
                    stored = com.eightfs.lumigather.data.StoredAuthToken(token, expiresAtEpochMs)
                }
                override fun clear() {
                    stored = null
                }
            },
            nowProvider = { 1_000_000L },
        )

    @Test
    fun firstLaunchWithoutAddressEntersSettings() = runBlocking {
        val holder = holderWith(MemoryAddressStore(""), RoutingTransport())
        holder.bootstrap()

        assertTrue(holder.state.settingsOpen, "没配过地址就该停在设置界面")
        assertFalse(holder.state.hasSavedAddress, "首次启动不该有可返回的入口")
        assertEquals(0, holder.state.categories.size)
    }

    @Test
    fun savedAddressLoadsCategoriesWithoutSettings() = runBlocking {
        val transport = RoutingTransport()
        val holder = holderWith(MemoryAddressStore("$SAMPLE_BASE_URL"), transport)
        holder.bootstrap()

        assertFalse(holder.state.settingsOpen)
        assertEquals(1, holder.state.categories.size)
        assertEquals("$SAMPLE_ROOT_PATH", holder.state.categories.first().rootPath)
        // 用的必须是存下来的地址
        assertTrue(transport.requestedUrls.all { it.startsWith("$SAMPLE_BASE_URL/") })
    }

    @Test
    fun saveAddressPersistsNormalizedValueAndReloadsImmediately() = runBlocking {
        val store = MemoryAddressStore("")
        val transport = RoutingTransport()
        val holder = holderWith(store, transport)
        holder.bootstrap()

        holder.updateAddressDraft("  http://server.test//  ")
        holder.saveAddress()

        // 落盘的是归一化后的值（去空格、去尾部斜杠）
        assertEquals("$SAMPLE_BASE_URL", store.stored())
        // 不重启也能进主界面：保存后立刻重跑了分类链路
        assertFalse(holder.state.settingsOpen)
        assertEquals(1, holder.state.categories.size)
        assertTrue(holder.state.hasSavedAddress)
    }

    @Test
    fun invalidAddressIsRejectedAndNothingPersisted() = runBlocking {
        val store = MemoryAddressStore("")
        val holder = holderWith(store, RoutingTransport())
        holder.bootstrap()

        holder.updateAddressDraft("h:9000")
        holder.saveAddress()

        assertEquals("", store.stored(), "非法地址绝不能落盘，否则下次冷启动直接坏掉")
        assertNotNull(holder.state.addressError)
        assertTrue(holder.state.settingsOpen)
    }

    @Test
    fun testConnectionReportsServerVersion() = runBlocking {
        val store = MemoryAddressStore("")
        val holder = holderWith(store, RoutingTransport())
        holder.bootstrap()

        holder.updateAddressDraft("$SAMPLE_BASE_URL")
        holder.testAddress()

        val testState = holder.state.testState
        assertTrue(testState is ConnectionTestState.Success, "可达的地址应给出成功")
        assertEquals("1.0.3", (testState as ConnectionTestState.Success).version)
        // 只测试不保存：草稿里的地址不该被持久化
        assertEquals("", store.stored())
    }

    @Test
    fun testConnectionFailureShowsChineseReason() = runBlocking {
        val unreachable = object : HttpTransport {
            override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
                throw IllegalStateException("connect timed out")
        }
        val holder = holderWith(MemoryAddressStore("$SAMPLE_BASE_URL"), unreachable)
        holder.bootstrap()

        // 主链路失败后进设置态；再测一次连接应拿到"无法连接"这类中文原因
        holder.openSettings()
        holder.updateAddressDraft("$SAMPLE_BASE_URL")
        holder.testAddress()

        val testState = holder.state.testState
        assertTrue(testState is ConnectionTestState.Failure)
        assertTrue(
            (testState as ConnectionTestState.Failure).message.contains("无法连接"),
            "失败提示要能指导用户（中文原因），实际是：${testState.message}",
        )
    }
}
