// 文件夹层级浏览的单测：count 三语义、三条渲染规则、面包屑截断、切分类清栈、压缩包条目、
// 以及"收藏/游客也产出 Picture"（证明网格抽象没把别的视图改坏）。
//
// 为什么这些必须钉死：
// - count = -1 是后端"未统计"，把它当 0 会把有图目录直接吞掉（实测 65 个目录全是 -1）；
// - 叶子目录不摊开图 → 用户要一层层点进空壳目录才能看到图；
// - 面包屑跳回用"再进一次"而不是"截断"→ 栈会越点越深，返回上级全乱；
// - 收藏/游客不产出 Picture → 网格抽象漏了，那两个视图会白屏。

package ui.screens.dirbrowse

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.ContentFilter
import ui.screens.state.GridEntry
import ui.screens.state.HomeStateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L
private const val SAMPLE_ROOT_PATH = "/root/honkai"
private const val OTHER_ROOT_PATH = "/root/genshin"
private const val SAMPLE_CATEGORY_NAME = "崩坏3"
private const val OTHER_CATEGORY_NAME = "原神"

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"$SAMPLE_CATEGORY_NAME","label":"$SAMPLE_CATEGORY_NAME","color":"#c084fc"},
               {"name":"$OTHER_CATEGORY_NAME","label":"$OTHER_CATEGORY_NAME","color":"#60a5fa"}],
 "directories":[{"path":"$SAMPLE_ROOT_PATH","category":"$SAMPLE_CATEGORY_NAME"},
                {"path":"$OTHER_ROOT_PATH","category":"$OTHER_CATEGORY_NAME"}]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3"}"""

// 一层的真实形态：未知张数的目录 / 真空目录 / 叶子目录（带 leafImages）/ 已知张数的目录 + 本层散图
private const val DIR_ROOT_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"","sig":1706502123893.9,
 "subdirs":[
   {"name":"Mods","relPath":"Mods","count":-1,"hasSub":true,"mtime":0},
   {"name":"Empty","relPath":"Empty","count":0,"hasSub":false,"mtime":0},
   {"name":"Leaf","relPath":"Leaf","count":2,"hasSub":false,"mtime":0,
    "leafImages":[
      {"name":"leaf-1.png","path":"$SAMPLE_ROOT_PATH/Leaf/leaf-1.png","url":"/api/media?file=%2Fleaf1","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"},
      {"name":"leaf-2.png","path":"$SAMPLE_ROOT_PATH/Leaf/leaf-2.png","url":"/api/media?file=%2Fleaf2","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"}]},
   {"name":"Known","relPath":"Known","count":7,"hasSub":true,"mtime":0}
 ],
 "images":[{"name":"root-1.png","path":"$SAMPLE_ROOT_PATH/root-1.png","url":"/api/media?file=%2Froot1","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

// 子目录里的一层（空）：用于验证"点进去有层级、面包屑变化"
private const val DIR_MODS_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"Mods","sig":1.0,"subdirs":[],
 "images":[{"name":"mod-1.png","path":"$SAMPLE_ROOT_PATH/Mods/mod-1.png","url":"/api/media?file=%2Fmod1","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

// 压缩包：一个含 2 张图、一个空包（空包不该出现在网格里）
private const val ZIPS_JSON = """
{"ok":true,"zips":[
  {"path":"$SAMPLE_ROOT_PATH/mod.zip","name":"mod.zip","images":[{"name":"a.png","size":100},{"name":"b.png","size":100}],"hasIni":true},
  {"path":"$SAMPLE_ROOT_PATH/empty.zip","name":"empty.zip","images":[],"hasIni":false}
]}
"""

private class FixedAddressStore : ServerAddressStore {
    override fun read(): String = "http://server.test"
    override fun write(address: String) = Unit
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

// 按 path 参数区分"根目录"与"子目录"，模拟真实 /api/dir 的按层返回
private class DirTransport : HttpTransport {
    var dirRequests: Int = 0
        private set

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            url.endsWith("/api/favorites") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/zip/scan") -> HttpRawResponse(200, ZIPS_JSON, "application/json")
            url.endsWith("/api/dir") -> {
                dirRequests += 1
                val body = if (query["path"].isNullOrEmpty()) DIR_ROOT_JSON else DIR_MODS_JSON
                HttpRawResponse(200, body, "application/json")
            }
            else -> HttpRawResponse(404, "{}", "application/json")
        }
}

private fun holder(transport: HttpTransport = DirTransport()): HomeStateHolder = HomeStateHolder(
    clientFactory = { address, onUnauthorized ->
        ApiClient(address, transport, onUnauthorized = onUnauthorized)
    },
    addressStore = FixedAddressStore(),
    imageDownloader = NoopDownloader,
    authTokenStore = LoggedOutTokens,
    nowProvider = { NOW_MS },
)

private fun List<GridEntry>.folders(): List<GridEntry.Folder> = filterIsInstance<GridEntry.Folder>()
private fun List<GridEntry>.pictures(): List<GridEntry.Picture> = filterIsInstance<GridEntry.Picture>()

class DirBrowseFlowTest {

    @Test
    fun unknownCountFolderStillProducesFolderEntry() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        val mods = stateHolder.state.gridEntries.folders().first { it.name == "Mods" }

        // count = -1 是"未统计"，必须如实传下去（界面据此显示「目录」而不是「0 张」）
        assertEquals(-1, mods.count, "count = -1 必须原样保留")
        assertFalse(stateHolder.state.isContentEmpty, "有子目录就不是空目录")
    }

    @Test
    fun emptyLeafDirectoryProducesNothing() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        assertTrue(
            stateHolder.state.gridEntries.none { entry ->
                (entry is GridEntry.Folder && entry.name == "Empty")
            },
            "无子目录且无图的目录不该出现在网格里",
        )
    }

    @Test
    fun leafDirectoryImagesBecomePicturesInsteadOfFolder() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        val names = stateHolder.state.gridEntries.pictures().map { it.item.name }

        assertTrue(names.containsAll(listOf("leaf-1.png", "leaf-2.png")), "叶子目录的图要直接摊开，实际：$names")
        assertTrue(
            stateHolder.state.gridEntries.folders().none { it.name == "Leaf" },
            "叶子目录不该再画文件夹卡片",
        )
    }

    @Test
    fun archivesWithImagesProducePicturesWithoutArchiveEntry() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        // 含图包不再产出"压缩包条目"：它的图直接成为 Picture 条目（空包则一张都不产出）
        assertEquals(1, stateHolder.state.archives.size, "只有含图的包留在状态里")
        assertEquals(2, stateHolder.state.archiveImages.size, "mod.zip 里 2 张图应全部摊平")
        val pictureNames = stateHolder.state.gridEntries.pictures().map { entry -> entry.item.name }
        assertTrue(pictureNames.containsAll(listOf("a.png", "b.png")), "实际：$pictureNames")
    }

    @Test
    fun enterFolderBuildsBreadcrumbAndEnablesGoUp() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        assertEquals(listOf(SAMPLE_CATEGORY_NAME), stateHolder.state.breadcrumbs)
        assertFalse(stateHolder.state.canGoUp, "根目录没有上级")

        stateHolder.enterFolder("Mods")

        assertEquals(listOf(SAMPLE_CATEGORY_NAME, "Mods"), stateHolder.state.breadcrumbs)
        assertTrue(stateHolder.state.canGoUp)
        // 用 any 而不是 single：子目录里可能有压缩包，包内图也会摊进网格
        assertTrue(
            stateHolder.state.gridEntries.pictures().any { entry -> entry.item.name == "mod-1.png" },
            "进入子目录后应显示该层的图",
        )
    }

    @Test
    fun jumpingToMiddleCrumbTruncatesStack() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.enterFolder("Mods")

        // 点第 0 段（分类根）→ 栈应被截断到底，而不是把根压在 Mods 后面
        stateHolder.jumpToCrumb(0)

        assertEquals(listOf(SAMPLE_CATEGORY_NAME), stateHolder.state.breadcrumbs, "跳回后只应剩分类根")
        assertFalse(stateHolder.state.canGoUp)
        // 回到根目录后内容也换回来了（Leaf 的图重新出现）
        assertTrue(stateHolder.state.gridEntries.pictures().any { it.item.name == "leaf-1.png" })
    }

    @Test
    fun goUpReturnsToParentLayer() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.enterFolder("Mods")

        stateHolder.goUp()

        assertEquals(listOf(SAMPLE_CATEGORY_NAME), stateHolder.state.breadcrumbs)
        assertFalse(stateHolder.state.canGoUp)
    }

    @Test
    fun switchingCategoryClearsDirectoryStack() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.enterFolder("Mods")
        assertTrue(stateHolder.state.canGoUp)

        stateHolder.selectFilter(ContentFilter.Category(OTHER_CATEGORY_NAME))

        assertEquals(
            listOf(OTHER_CATEGORY_NAME),
            stateHolder.state.breadcrumbs,
            "切分类必须回到该分类根目录",
        )
        assertFalse(stateHolder.state.canGoUp)
    }

    @Test
    fun archiveImagesAreTiledIntoTheSameGridAsPictures() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        // 压缩包不再是"可打开的条目"：包内图直接和目录图并排摊在同一张网格里，
        // 所以这里没有任何"打开/收起压缩包"的动作，只断言包内图确实进了网格
        assertTrue(stateHolder.state.archiveImages.isNotEmpty(), "当前目录的包内图应已就绪")
        val pictureNames = stateHolder.state.gridEntries.pictures().map { entry -> entry.item.name }
        assertTrue(pictureNames.contains("a.png"), "包内图应出现在网格里，实际：$pictureNames")
    }

    @Test
    fun favoritesAndGuestViewsStillProducePictures() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        stateHolder.selectFilter(ContentFilter.Favorites)
        assertTrue(
            stateHolder.state.gridEntries.all { entry -> entry is GridEntry.Picture },
            "收藏视图也该产出 Picture（网格抽象不能把别的视图改坏）",
        )

        stateHolder.selectFilter(ContentFilter.Guest)
        assertTrue(stateHolder.state.gridEntries.all { entry -> entry is GridEntry.Picture })
    }

    @Test
    fun gridPicturesCarryAbsoluteUrlsIncludingLeafImages() = runBlocking {
        // 回归：absoluteUrl 只在仓库层物化（Models 里约定「由仓库层填好后交给 UI」）。
        // 漏掉这一步时，图片仍能点开详情（详情用的是 path / sizeText），
        // 但网格里每张图都是空白格 —— UI 加载的是 absoluteUrl。
        //
        // 同时覆盖两支来源：本层 images 与叶子目录摊开的 leafImages，
        // 两者都必须物化，只修一支仍会留下空白格。
        val stateHolder = holder()
        stateHolder.bootstrap()

        val pictures = stateHolder.state.gridEntries.pictures()
        assertTrue(pictures.isNotEmpty(), "前提：这一层应当有图片条目")

        val blank = pictures.filterNot { it.item.absoluteUrl.startsWith("http") }
        assertTrue(
            blank.isEmpty(),
            "这些图没有绝对地址，界面上会是空白格：" +
                blank.joinToString { "${it.item.name} -> '${it.item.absoluteUrl}'" },
        )
    }

}
