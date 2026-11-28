// 压缩包区块的测试：扫当前目录、过滤空包、折叠、包级 GIF、换服务器清理、真·空目录判定。
//
// 形态前提（对齐网页版）：压缩包**不是独立视图**，是"当前目录内容"的一部分 ——
// 图片网格之后追加的一组可折叠包。所以这里的断言都围绕"当前目录"而不是"某个视图"。
//
// 这些点全是"不报错但结果是错的"，只能靠断言抓：
// - 扫错目录 → 显示别的分类的包，点开必失败；
// - 不过滤空包 → 一片点开是空的区块；
// - 折叠状态不放状态层 → 切分类回来全被展开；
// - GIF 状态做成全局单值 → 多包互相覆盖，第二个包的动图永远不显示。

package ui.screens.archive

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ArchiveHeuristics
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpBytesResponse
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.UrlBuilder
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.ContentFilter
import ui.screens.state.GifState
import ui.screens.state.GridEntry
import ui.screens.state.HomeStateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L

// 样本分类名与根路径：fixture 与断言共用一份
private const val SAMPLE_CATEGORY_NAME = "崩坏3"
private const val SAMPLE_ROOT_PATH = "/root/honkai"

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"$SAMPLE_CATEGORY_NAME","label":"$SAMPLE_CATEGORY_NAME","color":"#c084fc"}],
 "directories":[{"path":"$SAMPLE_ROOT_PATH","category":"$SAMPLE_CATEGORY_NAME"}]}
"""

private const val EMPTY_IMAGES_JSON = """
{"path":"$SAMPLE_ROOT_PATH","root":"$SAMPLE_ROOT_PATH","recursive":true,"count":0,"total":0,
 "offset":0,"limit":20,"returned":0,"images":[]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3"}"""

private const val EMPTY_DIR_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"","sig":0,"subdirs":[],"images":[]}
"""

// 三个包：mod 包（hasIni + speed，编排序列帧）、普通图包、空包（无图片条目）
private const val ZIPS_JSON = """
{"ok":true,"zips":[
  {"path":"$SAMPLE_ROOT_PATH/mod.zip","name":"mod.zip",
   "images":["贴图 001.png","贴图 002.png"],"hasIni":true,"speed":1.5},
  {"path":"$SAMPLE_ROOT_PATH/plain.zip","name":"plain.zip","images":["a.webp"],"hasIni":false},
  {"path":"$SAMPLE_ROOT_PATH/empty.zip","name":"empty.zip","images":[],"hasIni":false}
]}
"""

private class FixedAddressStore(private var address: String = "http://server.test") : ServerAddressStore {
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
 * - /api/zip/scan 返回 [scanCode]/[scanBody]
 * - getBytes（GIF 失败诊断）返回 [bytesCode] + [bytesBody]
 */
private class ArchiveTransport(
    private val scanCode: Int = 200,
    private val scanBody: String = ZIPS_JSON,
    private val bytesCode: Int = 400,
    private val bytesBody: String = """{"ok":false,"error":"压缩包无效"}""",
) : HttpTransport {

    var scanCount: Int = 0
    var lastScanDir: String? = null
    var bytesCount: Int = 0
    var lastBytesUrl: String = ""

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            // bootstrap 走 /api/dir：这里给"空目录"，压缩包断言只关心 zip/scan
            url.endsWith("/api/dir") -> HttpRawResponse(200, EMPTY_DIR_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            url.endsWith("/api/favorites") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/zip/scan") -> {
                scanCount += 1
                lastScanDir = query["dir"]
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

private fun holder(
    transport: HttpTransport,
    addressStore: ServerAddressStore = FixedAddressStore(),
): HomeStateHolder = HomeStateHolder(
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
    fun enteringCategoryScansZipsOfThatDirectory() = runBlocking {
        val transport = ArchiveTransport()
        val stateHolder = holder(transport)

        stateHolder.bootstrap()

        // 目录取自当前分类的绝对路径（不是用户手输、也不是根目录）
        assertEquals(SAMPLE_ROOT_PATH, stateHolder.state.archiveDir)
        assertEquals(SAMPLE_ROOT_PATH, transport.lastScanDir)
        assertEquals(3, stateHolder.state.archives.size, "扫描结果应原样保留（过滤在展示层做）")
    }

    @Test
    fun scanFailureShowsServerChineseMessage() = runBlocking {
        val transport = ArchiveTransport(scanCode = 400, scanBody = """{"ok":false,"error":"目录无效"}""")
        val stateHolder = holder(transport)

        stateHolder.bootstrap()

        val hint = stateHolder.state.archiveScanError.orEmpty()
        assertTrue(hint.contains("扫描压缩包失败"), "要说清做了什么，实际：$hint")
        assertTrue(hint.contains("目录无效"), "要用服务端原文，实际：$hint")
        assertTrue(stateHolder.state.archives.isEmpty())
        assertFalse(stateHolder.state.archivesLoading, "失败后不能一直停在加载态")
        // 关键：扫包失败不能占用那一行全局提示（否则会盖掉"登录已过期"这类更重要的消息）
        assertTrue(
            stateHolder.state.actionHint.isNullOrBlank(),
            "局部失败不该写全局提示，实际：${stateHolder.state.actionHint}",
        )
    }

    @Test
    fun emptyArchivesAreFilteredOutOfTheSection() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()

        val visible = stateHolder.state.visibleArchives

        assertEquals(2, visible.size, "没有图片条目的包不该出现在区块里")
        assertTrue(visible.none { it.name == "empty.zip" })
    }

    @Test
    fun toggleCollapsedFlipsPerPackageAndKeepsAcrossCategorySwitch() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }
        val plainZip = stateHolder.state.archives.first { it.name == "plain.zip" }
        assertFalse(stateHolder.state.isArchiveCollapsed(modZip), "默认展开")

        stateHolder.toggleArchiveCollapsed(modZip.path)
        assertTrue(stateHolder.state.isArchiveCollapsed(modZip))
        assertFalse(stateHolder.state.isArchiveCollapsed(plainZip), "折叠是按包记的，不能连带别的包")

        // 切一次分类再回来：折叠选择仍在（状态层持有，不是组件内临时变量）
        stateHolder.selectFilter(ContentFilter.Category(SAMPLE_CATEGORY_NAME))
        assertTrue(stateHolder.state.isArchiveCollapsed(modZip), "切分类后折叠状态应保留")

        stateHolder.toggleArchiveCollapsed(modZip.path)
        assertFalse(stateHolder.state.isArchiveCollapsed(modZip))
    }

    @Test
    fun zipImagesOfEncodesEntryNamesAndSkipsUnsafeOnes() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }
        // 追加一条路径穿越尝试：服务端会 400，客户端应提前过滤
        val withUnsafe = modZip.copy(images = modZip.images + "../escape.png")

        val images = stateHolder.zipImagesOf(withUnsafe)

        assertEquals(2, images.size, "含 .. 的条目应被提前排除")
        val url = images[0].absoluteUrl
        assertTrue(url.contains("/api/zip/img"), "应指向包内图片接口，实际：$url")
        assertTrue(
            url.contains("path=" + UrlBuilder.percentEncode(withUnsafe.path)),
            "zip 路径要按绝对路径编码（前导斜杠也编码），实际：$url",
        )
        assertTrue(url.contains("%E8%B4%B4%E5%9B%BE"), "中文条目名要按 UTF-8 编码，实际：$url")
        assertTrue(url.contains("%20"), "条目名里的空格要编码，实际：$url")
        assertEquals("贴图 001.png", images[0].name)
    }

    @Test
    fun gifStateIsPerPackageAndRequestIsDeduped() = runBlocking {
        val stateHolder = holder(ArchiveTransport())
        stateHolder.bootstrap()
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }
        val plainZip = stateHolder.state.archives.first { it.name == "plain.zip" }

        stateHolder.requestZipGif(modZip.path)
        val modState = stateHolder.state.gifStateOf(modZip)
        assertTrue(modState is GifState.Generating)
        val url = (modState as GifState.Generating).url
        assertTrue(url.contains("/api/zip/gif"), "包内动图该走包内接口，实际：$url")
        assertTrue(url.contains("fps=60"), "要带 fps（服务端用 ini 的 speed 换算），实际：$url")

        // 防重复：同一包生成中再请求不变
        stateHolder.requestZipGif(modZip.path)
        assertEquals(url, (stateHolder.state.gifStateOf(modZip) as GifState.Generating).url)

        // 另一个包独立：不会因为 A 在生成就挡掉 B
        stateHolder.requestZipGif(plainZip.path)
        assertTrue(stateHolder.state.gifStateOf(plainZip) is GifState.Generating)
        assertTrue(
            stateHolder.state.gifStateOf(modZip) is GifState.Generating,
            "B 的请求不能覆盖 A 的状态（这就是状态做成 per-package 的原因）",
        )

        // 加载成功 → 该包就绪
        stateHolder.onZipGifLoaded(modZip.path, url)
        assertTrue(stateHolder.state.gifStateOf(modZip) is GifState.Ready)
    }

    @Test
    fun gifFailureAsksServerForReasonThroughBinaryChannel() = runBlocking {
        val transport = ArchiveTransport(bytesCode = 400, bytesBody = """{"ok":false,"error":"压缩包无效"}""")
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }

        stateHolder.requestZipGif(modZip.path)
        val url = (stateHolder.state.gifStateOf(modZip) as GifState.Generating).url
        stateHolder.onZipGifLoadFailed(modZip.path, url)

        assertEquals(1, transport.bytesCount, "失败时要走二进制通道问一次原因")
        assertEquals(url, transport.lastBytesUrl)
        val failed = stateHolder.state.gifStateOf(modZip)
        assertTrue(failed is GifState.Failed)
        assertTrue(
            (failed as GifState.Failed).message.contains("压缩包无效"),
            "要用服务端那句中文，实际：${failed.message}",
        )

        // 重试：回到 Idle（界面随后会重新触发一次），不会自动无限重试
        stateHolder.resetZipGif(modZip.path)
        assertEquals(GifState.Idle, stateHolder.state.gifStateOf(modZip))
    }

    @Test
    fun emptyDirectoryNeedsBothImagesAndArchivesEmpty() = runBlocking {
        // 无图但有包：内容其实在区块里，不能显示"还没有图片"
        val withZips = holder(ArchiveTransport())
        withZips.bootstrap()
        // 网格现在是同质条目：没图不等于没内容（压缩包会作为 Archive 条目出现）
        assertTrue(
            withZips.state.gridEntries.none { entry -> entry is GridEntry.Picture },
            "这层没有图片条目",
        )
        assertFalse(withZips.state.isContentEmpty, "有压缩包就不算空目录")

        // 无图无包：真·空目录
        val noZips = holder(ArchiveTransport(scanBody = """{"ok":true,"zips":[]}"""))
        noZips.bootstrap()
        assertTrue(noZips.state.isContentEmpty)
    }

    @Test
    fun changingServerClearsArchiveState() = runBlocking {
        val addressStore = FixedAddressStore()
        val transport = ArchiveTransport()
        val stateHolder = holder(transport, addressStore)
        stateHolder.bootstrap()
        val modZip = stateHolder.state.archives.first { it.name == "mod.zip" }
        stateHolder.toggleArchiveCollapsed(modZip.path)
        stateHolder.requestZipGif(modZip.path)
        assertTrue(stateHolder.state.archives.isNotEmpty())

        stateHolder.openSettings()
        stateHolder.updateAddressDraft("http://other.test")
        stateHolder.saveAddress()

        // 折叠键与 GIF 状态是"旧服务器的路径/URL"，必须清
        assertTrue(stateHolder.state.collapsedArchives.isEmpty(), "旧服务器的折叠键没意义，要清")
        assertTrue(stateHolder.state.zipGifStates.isEmpty(), "旧服务器的 GIF 状态要清")
        // 列表本身会被新地址重新扫一遍填回来（这是正确行为：新服务器同路径下也可能有包），
        // 用扫描次数证明"确实重扫过"，而不是留着旧结果
        assertTrue(transport.scanCount >= 2, "换地址后应重新扫描，实际次数：${transport.scanCount}")
        assertEquals(SAMPLE_ROOT_PATH, stateHolder.state.archiveDir)
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
