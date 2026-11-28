// 压缩包的单测：扫当前目录、空包过滤（回归重点）、包内图摊成普通图片、失败提示、
// 真·空目录判定、换服务器清理。
//
// 形态前提（T22 定的）：**压缩包不是一种条目类型**，也不是"打开"的对象 ——
// /api/zip/scan 只扫当前层，包内图直接当普通图片进网格（服务端 /api/zip/img 逐张给图）。
// 所以这里不再有 GifState / 折叠 / 展开这些概念，断言全部落在 archiveImages 与 gridEntries 上。
//
// 这些点是"不报错但结果是错的"，只能靠断言抓：
// - 不过滤空包 → 网格里一堆点开什么都没有的「0 张」卡片（实测同一目录 70/108 个包 images 为空）
// - 包内图不摊平 → 用户看不到包里的图，还得先"打开"
// - 客户端自己判序列帧/请求合成 → 与 service 端 gif-rule 双份规则，必然漂移

package ui.screens.archive

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.UrlBuilder
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.GridEntry
import ui.screens.state.HomeStateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L
private const val SAMPLE_ROOT_PATH = "/root/honkai"
private const val SAMPLE_CATEGORY_NAME = "崩坏3"

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"$SAMPLE_CATEGORY_NAME","label":"$SAMPLE_CATEGORY_NAME","color":"#c084fc"}],
 "directories":[{"path":"$SAMPLE_ROOT_PATH","category":"$SAMPLE_CATEGORY_NAME"}]}
"""

private const val EMPTY_DIR_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"","sig":0,"subdirs":[],"images":[]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3"}"""

/**
 * 10 个包，其中 6 个 images 为空（真实数据里空包占多数：实测 108 个包里 70 个为空）。
 * 留下的 4 个各有 1 张图 —— 所以网格里应该出现 4 个图片条目。
 *
 * fixture 的 images **必须是对象数组** `{name,size}`：服务端就是这样返回的
 * （`server/lib/archive.js:203`）。写成字符串数组会掩盖"解析出空名字 → 图片全不加载"的真实故障。
 */
private fun zipsJson(): String = buildString {
    append("""{"ok":true,"zips":[""")
    // 4 个有图包
    repeat(4) { index ->
        append(
            """{"path":"$SAMPLE_ROOT_PATH/pack-$index.zip","name":"pack-$index.zip",""" +
                """"images":[{"name":"图 $index.png","size":1024}],"hasIni":false,"speed":null},""",
        )
    }
    // 6 个空包
    repeat(6) { index ->
        append("""{"path":"$SAMPLE_ROOT_PATH/empty-$index.zip","name":"empty-$index.zip","images":[],"hasIni":false},""")
    }
    append("]}")
}

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

private class ArchiveTransport(
    private val scanCode: Int = 200,
    private val scanBody: String = zipsJson(),
) : HttpTransport {
    var scanCount: Int = 0
        private set

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/dir") -> HttpRawResponse(200, EMPTY_DIR_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            url.endsWith("/api/favorites") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/zip/scan") -> {
                scanCount += 1
                HttpRawResponse(scanCode, scanBody, "application/json")
            }
            else -> HttpRawResponse(404, "{}", "application/json")
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

private fun HomeStateHolder.pictures(): List<GridEntry.Picture> =
    state.gridEntries.filterIsInstance<GridEntry.Picture>()

class ArchiveFlowTest {

    @Test
    fun emptyArchivesAreFilteredOutOfTheGrid() = runBlocking {
        val stateHolder = holder(ArchiveTransport())

        stateHolder.bootstrap()

        // 10 个包进来，6 个空包必须被滤掉
        assertEquals(4, stateHolder.state.archives.size, "空包不该留在状态里")
        assertTrue(
            stateHolder.state.archives.none { entry -> entry.name.startsWith("empty-") },
            "空包名一个都不该出现",
        )
    }

    @Test
    fun zipImagesBecomePlainPicturesWithoutAnyArchiveCard() = runBlocking {
        val stateHolder = holder(ArchiveTransport())

        stateHolder.bootstrap()

        val pictures = stateHolder.pictures()
        assertEquals(4, pictures.size, "4 个有图包 × 1 张 = 4 个图片条目，实际：${pictures.map { it.item.name }}")
        // 网格里只有图片条目：没有"压缩包卡片"这种类型（T22 之前是 GridEntry.Archive）
        assertEquals(
            stateHolder.state.gridEntries.size,
            pictures.size,
            "网格里不该有非图片条目（这个目录没有子目录）",
        )
        // 包内图的 URL 必须是 /api/zip/img 且带 path/file 两个参数
        val url = pictures.first().item.absoluteUrl
        assertTrue(url.contains("/api/zip/img"), "实际：$url")
        assertTrue(
            url.contains("path=" + UrlBuilder.percentEncode("$SAMPLE_ROOT_PATH/pack-0.zip")),
            "包路径要按绝对路径编码，实际：$url",
        )
        assertTrue(url.contains("file="), "要带包内条目名，实际：$url")
        // 关键回归：file= 后面必须是**真实条目名**（曾因把对象数组按字符串解析，
        // 这里拿到的是空串，于是整批包内图请求 400、界面全部"无法加载"）
        assertTrue(
            url.contains("file=" + UrlBuilder.percentEncode("图 0.png")),
            "file 参数必须是包内真实条目名，实际：$url",
        )
        assertTrue(url.contains("%E5%9B%BE"), "中文条目名要按 UTF-8 编码，实际：$url")
    }

    @Test
    fun noZeroCountCardAppearsForEmptyArchives() = runBlocking {
        val stateHolder = holder(ArchiveTransport())

        stateHolder.bootstrap()

        // 「0 张」只会来自空包卡片；T22 后压缩包不再有卡片，所以整张网格里不应出现 0 张的概念。
        // 直接断言没有任何条目的"张数"是 0（Folder 才有 count 字段，包内图是 Picture）
        assertTrue(
            stateHolder.state.gridEntries.none { entry ->
                entry is GridEntry.Folder && entry.count == 0
            },
            "不应出现张数为 0 的卡片",
        )
        assertTrue(stateHolder.state.archives.all { it.images.isNotEmpty() })
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
        // 关键：扫包失败不能占用那一行全局提示（否则会盖掉"登录已过期"这类更重要的消息）
        assertTrue(
            stateHolder.state.actionHint.isNullOrBlank(),
            "局部失败不该写全局提示，实际：${stateHolder.state.actionHint}",
        )
    }

    @Test
    fun emptyDirectoryNeedsBothImagesAndArchivesEmpty() = runBlocking {
        // 无图但有包：包内图会摊进网格，不算空目录
        val withZips = holder(ArchiveTransport())
        withZips.bootstrap()
        assertTrue(withZips.pictures().isNotEmpty())
        assertFalse(withZips.state.isContentEmpty, "有包内图就不算空目录")

        // 无图无包：真·空目录
        val noZips = holder(ArchiveTransport(scanBody = """{"ok":true,"zips":[]}"""))
        noZips.bootstrap()
        assertTrue(noZips.state.isContentEmpty)
        assertTrue(noZips.state.archiveImages.isEmpty())
    }

    @Test
    fun changingServerClearsArchiveState() = runBlocking {
        val addressStore = FixedAddressStore()
        val transport = ArchiveTransport()
        val stateHolder = holder(transport, addressStore)
        stateHolder.bootstrap()
        assertTrue(stateHolder.state.archives.isNotEmpty())

        stateHolder.openSettings()
        stateHolder.updateAddressDraft("http://other.test")
        stateHolder.saveAddress()

        assertNull(stateHolder.state.archiveScanError, "换服务器后错误态要清")
        // 列表与包内图会被新地址重新扫一遍填回来（这是正确行为：新服务器同路径下也可能有包），
        // 用扫描次数证明"确实重扫过"，而不是留着旧结果
        assertTrue(transport.scanCount >= 2, "换地址后应重新扫描，实际：${transport.scanCount}")
        assertEquals(4, stateHolder.state.archives.size, "应是新服务器扫出来的结果")
    }

    @Test
    fun archiveEntrySpeedIsOptional() {
        // speed 缺字段必须是 null（有 ini 才有值）；客户端不做 ini 换算，只是原样带过来
        val plain = ArchiveEntry(name = "p.zip", hasIni = false)
        assertNull(plain.speed)
        val animated = ArchiveEntry(name = "a.zip", hasIni = true, speed = 1.5)
        assertEquals(1.5, animated.speed)
    }
}
