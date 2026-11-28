// 图片交互改版的单测：点按放大（单选语义）、再点收起、切换目标、各种换内容后清空，
// 以及"长按不能串成放大、点按不能串成开右栏"这两条互斥。
//
// 手势本身（combinedClickable 的 onLongClick）在无设备环境里测不到，所以这里测的是
// **回调落到的状态语义**：点按 → 只动 expandedEntryKey；长按 → 只动 selectedImage。
// 两者互不改变对方，就是"没串"的机器证明。

package ui.screens.gridgesture

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L
private const val SAMPLE_ROOT_PATH = "/root/honkai"
private const val SAMPLE_CATEGORY_NAME = "崩坏3"
private const val OTHER_CATEGORY_NAME = "原神"

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"$SAMPLE_CATEGORY_NAME","label":"$SAMPLE_CATEGORY_NAME","color":"#c084fc"},
               {"name":"$OTHER_CATEGORY_NAME","label":"$OTHER_CATEGORY_NAME","color":"#60a5fa"}],
 "directories":[{"path":"$SAMPLE_ROOT_PATH","category":"$SAMPLE_CATEGORY_NAME"}]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3"}"""

// 本层三张图（放大切换需要 ≥2 张才能验"旧的自动恢复"）
private const val DIR_ROOT_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"","sig":1.0,"subdirs":[],
 "images":[
   {"name":"a.png","path":"$SAMPLE_ROOT_PATH/a.png","url":"/api/media?file=%2Fa","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"},
   {"name":"b.png","path":"$SAMPLE_ROOT_PATH/b.png","url":"/api/media?file=%2Fb","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"},
   {"name":"c.png","path":"$SAMPLE_ROOT_PATH/c.png","url":"/api/media?file=%2Fc","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

private const val DIR_MODS_JSON = """
{"root":"$SAMPLE_ROOT_PATH","path":"Mods","sig":1.0,"subdirs":[],
 "images":[{"name":"mod-1.png","path":"$SAMPLE_ROOT_PATH/Mods/mod-1.png","url":"/api/media?file=%2Fmod1","size":"1 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

private const val ZIPS_JSON = """
{"ok":true,"zips":[{"path":"$SAMPLE_ROOT_PATH/mod.zip","name":"mod.zip","images":["a.png"],"hasIni":false}]}
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

// 根层有子目录（用于 enterFolder），子层只有一张图
private class GestureTransport : HttpTransport {
    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            url.endsWith("/api/favorites") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, "[]", "application/json")
            url.endsWith("/api/zip/scan") -> HttpRawResponse(200, ZIPS_JSON, "application/json")
            url.endsWith("/api/dir") -> {
                val body = if (query["path"].isNullOrEmpty()) {
                    // 根层补一个子目录，enterFolder 才有目标
                    DIR_ROOT_JSON.replace("\"subdirs\":[]", "\"subdirs\":[{\"name\":\"Mods\",\"relPath\":\"Mods\",\"count\":1,\"hasSub\":false,\"mtime\":0,\"leafImages\":[]}]")
                } else {
                    DIR_MODS_JSON
                }
                HttpRawResponse(200, body, "application/json")
            }
            else -> HttpRawResponse(404, "{}", "application/json")
        }
}

private fun holder(): HomeStateHolder = HomeStateHolder(
    clientFactory = { address, onUnauthorized ->
        ApiClient(address, GestureTransport(), onUnauthorized = onUnauthorized)
    },
    addressStore = FixedAddressStore(),
    imageDownloader = NoopDownloader,
    authTokenStore = LoggedOutTokens,
    nowProvider = { NOW_MS },
)

// 网格里的图片条目（放大只对图片生效）
private fun HomeStateHolder.pictures(): List<GridEntry.Picture> =
    state.gridEntries.filterIsInstance<GridEntry.Picture>()

private fun HomeStateHolder.keyOf(name: String): String =
    pictures().first { it.item.name == name }.item.absoluteUrl

class GridInteractionTest {

    @Test
    fun tapExpandsPictureAndRecordsItsKey() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        val key = stateHolder.keyOf("a.png")

        stateHolder.toggleEntryExpanded(key)

        assertEquals(key, stateHolder.state.expandedEntryKey)
        assertTrue(stateHolder.state.isExpanded(key))
    }

    @Test
    fun keyIsAbsoluteUrlSoLeafImagesCannotCollide() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        val key = stateHolder.keyOf("a.png")

        // key 必须是绝对地址：叶子目录摊平后同名文件会共存于同一层，文件名不唯一
        assertTrue(key.startsWith("http"), "放大 key 应是绝对地址，实际：$key")
    }

    @Test
    fun tapSamePictureAgainCollapses() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        val key = stateHolder.keyOf("a.png")
        stateHolder.toggleEntryExpanded(key)

        stateHolder.toggleEntryExpanded(key)

        assertNull(stateHolder.state.expandedEntryKey, "再点同一张应收起")
    }

    @Test
    fun tapAnotherPictureSwitchesSingleSelection() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        val keyA = stateHolder.keyOf("a.png")
        val keyB = stateHolder.keyOf("b.png")

        stateHolder.toggleEntryExpanded(keyA)
        stateHolder.toggleEntryExpanded(keyB)

        assertEquals(keyB, stateHolder.state.expandedEntryKey, "应切换到新的那张")
        assertTrue(!stateHolder.state.isExpanded(keyA), "旧的自动恢复 1×1（单选语义）")
    }

    @Test
    fun enteringFolderClearsExpanded() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.toggleEntryExpanded(stateHolder.keyOf("a.png"))

        stateHolder.enterFolder("Mods")

        assertNull(stateHolder.state.expandedEntryKey, "换目录必须清空，否则会莫名放大")
    }

    @Test
    fun goingUpClearsExpanded() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.enterFolder("Mods")
        stateHolder.toggleEntryExpanded(stateHolder.keyOf("mod-1.png"))

        stateHolder.goUp()

        assertNull(stateHolder.state.expandedEntryKey)
    }

    @Test
    fun switchingCategoryClearsExpanded() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.toggleEntryExpanded(stateHolder.keyOf("a.png"))

        stateHolder.selectFilter(ContentFilter.Category(OTHER_CATEGORY_NAME))

        assertNull(stateHolder.state.expandedEntryKey)
    }

    @Test
    fun switchingViewClearsExpanded() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        stateHolder.toggleEntryExpanded(stateHolder.keyOf("a.png"))

        stateHolder.selectFilter(ContentFilter.Favorites)

        assertNull(stateHolder.state.expandedEntryKey, "切到收藏视图也要清空")
    }

    @Test
    fun longPressPathDoesNotToggleExpanded() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()
        val picture = stateHolder.pictures().first { it.item.name == "a.png" }

        // 长按走 openDetail（打开右边栏），不该动放大态
        stateHolder.openDetail(picture.item)

        assertNull(stateHolder.state.expandedEntryKey, "长按不能串成放大")
        assertEquals("a.png", stateHolder.state.selectedImage?.name, "长按应打开右边栏")
    }

    @Test
    fun tapDoesNotOpenDetailPanel() = runBlocking {
        val stateHolder = holder()
        stateHolder.bootstrap()

        // 点按只切放大，不该打开右边栏
        stateHolder.toggleEntryExpanded(stateHolder.keyOf("a.png"))

        assertNull(stateHolder.state.selectedImage, "点按不能串成打开右栏")
        assertEquals(false, stateHolder.state.rightSidebarOpen)
    }
}
