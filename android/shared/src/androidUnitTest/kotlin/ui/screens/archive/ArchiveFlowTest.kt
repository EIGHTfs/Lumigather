// 压缩包与序列帧的测试：scan、包内 URL 拼装（含条目名转义）、GIF 生成态/失败原因、换服务器清理、防重复。
//
// 这些点全都"不报错但结果是错的"，只能靠断言抓：
// - 条目名不编码 → 中文/空格文件名请求不到（服务端当成别的条目）；
// - `..` 不拦 → 网格里挂一堆必然 400 的图；
// - GIF 失败时不去问服务端原因 → 用户只看到"合成失败"，不知道是"没序列帧"还是"网络断了"；
// - 换服务器不清 → 上一台服务器的包名还在列表里，点开必失败。

package ui.screens.archive

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ArchiveHeuristics
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpBytesResponse
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.GifState
import ui.screens.state.HomeStateHolder
import ui.screens.state.HomeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"崩坏3","label":"崩坏3","color":"#c084fc"}],
 "directories":[{"path":"test-root/honkai","category":"崩坏3"}]}
"""

private const val EMPTY_IMAGES_JSON = """
{"path":"test-root/honkai","root":"test-root/honkai","recursive":true,"count":0,"total":0,
 "offset":0,"limit":20,"returned":0,"images":[]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3"}"""

// 两个包：一个 mod 包（hasIni+speed），一个普通图包；包内含中文/空格条目与一条穿越尝试
private const val ZIPS_JSON = """
{"ok":true,"zips":[
  {"path":"test-root/honkai/mod.zip","name":"mod.zip",
   "images":["贴图 001.png","贴图 002.png","../escape.png"],"hasIni":true,"speed":1.5},
  {"path":"test-root/honkai/plain.zip","name":"plain.zip","images":["a.webp"],"hasIni":false}
]}
"""

private class FixedAddressStore(private var address: String = "http://h:8081") : ServerAddressStore {
    override fun read(): String = address
    override fun write(address: String) {
        this.address = address
    }
}

private object LoggedOutTokens : AuthTokenStore {
    override fun read() = null
    override fun write(token: String, expiresAtEpochMs: Long) = Unit
    override fun clear() = Unit
}

private object NoopDownloader : ImageDownloader {
    override suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult =
        DownloadResult.Failure(DownloadFailureKind.Unknown, "测试不下载")
}

/**
 * 可编排的假传输层：
 * - /api/zip/scan 返回 [scanBody]/[scanCode]
 * - getBytes（GIF 失败诊断）返回 [bytesCode] + [bytesBody]
 */
private class ArchiveTransport(
    private val scanCode: Int = 200,
    private val scanBody: String = ZIPS_JSON,
    private val bytesCode: Int = 400,
    private val bytesBody: String = """{"ok":false,"error":"压缩包无效"}""",
) : HttpTransport {

    var scanCount: Int = 0
    var bytesCount: Int = 0
    var lastBytesUrl: String = ""

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/images") -> HttpRawResponse(200, EMPTY_IMAGES_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            url.endsWith("/api/favorites") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/zip/scan") -> {
                scanCount += 1
                HttpRawResponse(scanCode, scanBody, "application/json")
            }
            else -> HttpRawResponse(404, "{}", "application/json")
        }

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        timeoutMs: Long,
    ): HttpBytesResponse {
        bytesCount += 1
        lastBytesUrl = url
        return HttpBytesResponse(bytesCode, bytesBody.toByteArray(), "application/json")
    }
}

private fun holder(transport: HttpTransport, addressStore: ServerAddressStore = FixedAddressStore()): HomeStateHolder =
    HomeStateHolder(
        clientFactory = { address, onUnauthorized ->
            ApiClient(address, transport, onUnauthorized = onUnauthorized)
        },
        addressStore = addressStore,
        imageDownloader = NoopDownloader,
        authTokenStore = LoggedOutTokens,
        nowProvider = { NOW_MS },
    )

class ArchiveFlowTest {

    @Test
    fun scanSuccessPopulatesArchiveListWithIniFlagAndSpeed() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()

        stateHolder.selectView(HomeView.Archive)

        val archives = stateHolder.state.archives
        assertEquals(2, archives.size)
        assertEquals("mod.zip", archives[0].name)
        assertTrue(archives[0].hasIni, "含 ini 的包要标记成 mod 包")
        assertEquals(1.5, archives[0].speed)
        assertFalse(archives[1].hasIni)
        // 目录取自当前分类的绝对路径
        assertEquals("test-root/honkai", stateHolder.state.archiveDir)
    }

    @Test
    fun scanFailureShowsServerChineseMessage() = runBlocking {
        val transport = ArchiveTransport(scanCode = 400, scanBody = """{"ok":false,"error":"目录无效"}""")
        val stateHolder = holder(transport)
        stateHolder.bootstrap()

        stateHolder.selectView(HomeView.Archive)

        val hint = stateHolder.state.actionHint.orEmpty()
        assertTrue(hint.contains("扫描压缩包失败"), "要说清做了什么，实际：$hint")
        assertTrue(hint.contains("目录无效"), "要用服务端原文，实际：$hint")
        assertTrue(stateHolder.state.archives.isEmpty())
    }

    @Test
    fun openArchiveEncodesEntryNamesAndSkipsUnsafeOnes() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Archive)
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }

        stateHolder.openArchive(modZip)

        val images = stateHolder.state.archiveImages
        // "../escape.png" 被过滤：服务端会 400，不必挂进网格
        assertEquals(2, images.size, "含 .. 的条目应被客户端提前排除")
        val url = images[0].absoluteUrl
        assertTrue(url.contains("/api/zip/img"), "应指向包内图片接口，实际：$url")
        assertTrue(url.contains("path=test-root%2Fhonkai%2Fmod.zip"), "zip 路径要编码，实际：$url")
        assertTrue(url.contains("%E8%B4%B4%E5%9B%BE"), "中文条目名要按 UTF-8 编码，实际：$url")
        assertTrue(url.contains("%20"), "条目名里的空格要编码，实际：$url")
        assertEquals("贴图 001.png", images[0].name)
    }

    @Test
    fun gifRequestUsesZipEndpointWhenArchiveOpenedAndDedupesWhileGenerating() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Archive)
        stateHolder.openArchive(stateHolder.state.archives.first { it.name == "mod.zip" })

        stateHolder.requestGif()
        val first = stateHolder.state.gifState
        assertTrue(first is GifState.Generating, "请求后应进入生成中")
        val firstUrl = (first as GifState.Generating).url
        assertTrue(firstUrl.contains("/api/zip/gif"), "包内视图该用包内接口，实际：$firstUrl")
        assertTrue(firstUrl.contains("fps=60"), "要带 fps 参数（服务端用 ini 的 speed 换算），实际：$firstUrl")

        // 防重复：生成中再次请求不应变（避免排多个服务端任务）
        stateHolder.requestGif()
        assertEquals(first.url, (stateHolder.state.gifState as GifState.Generating).url)

        // 加载成功 → 就绪；此时再点应允许重新合成
        stateHolder.onGifLoaded(firstUrl)
        assertTrue(stateHolder.state.gifState is GifState.Ready)
        stateHolder.requestGif()
        assertTrue(stateHolder.state.gifState is GifState.Generating)
    }

    @Test
    fun directoryGifUsesDirEndpointWhenNoArchiveOpened() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Archive)

        stateHolder.requestGif()

        val url = (stateHolder.state.gifState as GifState.Generating).url
        assertTrue(url.contains("/api/gif"), "目录视图该用目录接口，实际：$url")
        assertTrue(url.contains("dir=test-root%2Fhonkai"), "参数名必须是 dir，实际：$url")
    }

    @Test
    fun gifFailureAsksServerForReasonThroughBinaryChannel() = runBlocking {
        val transport = ArchiveTransport(bytesCode = 400, bytesBody = """{"ok":false,"error":"压缩包无效"}""")
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Archive)
        stateHolder.openArchive(stateHolder.state.archives.first())

        stateHolder.requestGif()
        val url = (stateHolder.state.gifState as GifState.Generating).url
        stateHolder.onGifLoadFailed(url)

        assertEquals(1, transport.bytesCount, "失败时要走二进制通道问一次原因")
        assertEquals(url, transport.lastBytesUrl)
        val state = stateHolder.state.gifState
        assertTrue(state is GifState.Failed)
        assertTrue(
            (state as GifState.Failed).message.contains("压缩包无效"),
            "要用服务端那句中文，实际：${state.message}",
        )
    }

    @Test
    fun changingServerClearsArchiveState() = runBlocking {
        val addressStore = FixedAddressStore()
        val stateHolder = holder(ArchiveTransport(), addressStore)
        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Archive)
        stateHolder.openArchive(stateHolder.state.archives.first())
        stateHolder.requestGif()
        assertTrue(stateHolder.state.archives.isNotEmpty())

        stateHolder.openSettings()
        stateHolder.updateAddressDraft("http://other:8081")
        stateHolder.saveAddress()

        assertEquals("", stateHolder.state.archiveDir, "换服务器后目录要清")
        assertTrue(stateHolder.state.archives.isEmpty(), "换服务器后压缩包列表要清")
        assertEquals(null, stateHolder.state.openedArchive, "换服务器后包内状态要清")
        assertTrue(stateHolder.state.archiveImages.isEmpty())
        assertEquals(GifState.Idle, stateHolder.state.gifState, "换服务器后已合成的 GIF 要清")
        assertEquals(HomeView.All, stateHolder.state.view)
    }

    @Test
    fun archiveViewWithoutCategoryGivesActionableHint() = runBlocking {
        // 不进 bootstrap：此时没有任何分类，压缩包视图没有可扫描的目录
        val stateHolder = holder(ArchiveTransport())

        stateHolder.selectView(HomeView.Archive)

        val hint = stateHolder.state.actionHint.orEmpty()
        assertTrue(hint.contains("请先选择"), "没有目录时要给出可操作提示，实际：$hint")
    }
}

class ArchiveHeuristicsTest {

    @Test
    fun numberedFramesLookLikeSequence() {
        assertTrue(ArchiveHeuristics.looksLikeSequence(listOf("frame001.png", "frame002.png")))
    }

    @Test
    fun unrelatedNamesDoNotLookLikeSequence() {
        assertFalse(ArchiveHeuristics.looksLikeSequence(listOf("a.png", "b.png")))
        assertFalse(ArchiveHeuristics.looksLikeSequence(listOf("only.png")))
        assertFalse(ArchiveHeuristics.looksLikeSequence(emptyList()))
    }

    @Test
    fun unsafeEntryNamesAreRejected() {
        assertTrue(ArchiveHeuristics.isSafeEntryName("a/b.png"))
        assertFalse(ArchiveHeuristics.isSafeEntryName("../x.png"))
        assertFalse(ArchiveHeuristics.isSafeEntryName(""))
    }

    @Test
    fun archiveEntrySpeedIsOptional() {
        // speed 缺字段必须是 null（显示"倍速 0"会误导），有值才是 Double
        val plain = ArchiveEntry(name = "p.zip", hasIni = false)
        assertEquals(null, plain.speed)
    }
}
