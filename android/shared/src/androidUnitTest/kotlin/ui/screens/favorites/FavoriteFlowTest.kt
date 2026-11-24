// 收藏子状态机的测试：未登录拦截、增删、alreadyExists、src 规范化去重、失败提示、三视图互斥。
//
// 为什么这些必须在没有服务端的情况下钉死：
// - 「未登录点收藏」如果漏了拦截，就会发一个必然 401 的写请求，还会把用户的登录态一起清掉；
// - 「同一个文件两种 src 写法」如果不去重，相册里一张图会出现两次，而且计数虚高；
// - 「alreadyExists 当失败」会让用户以为收藏没成功，反复点。
// 这三类都不是崩溃，而是"看起来正常但结果是错的"，只能靠断言抓。

package ui.screens.favorites

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.StoredAuthToken
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.HomeStateHolder
import ui.screens.state.HomeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L
private const val ONE_HOUR_MS = 3_600_000L

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"崩坏3","label":"崩坏3","color":"#c084fc"}],
 "directories":[{"path":"test-root/honkai","category":"崩坏3"}]}
"""

private const val IMAGES_JSON = """
{"path":"test-root/honkai","root":"test-root/honkai","recursive":true,"count":1,"total":1,
 "offset":0,"limit":20,"returned":1,
 "images":[{"name":"a.png","path":"test-root/honkai/a.png","url":"/api/media?file=%2Fa.png",
            "size":"1.0 KB","modified":"2023-08-18T06:56:03.097Z"}]}
"""

private const val STATUS_JSON = """{"ok":true,"version":"1.0.3","needsAuth":true,"authed":true}"""

// 选中的那张图（与 IMAGES_JSON 同一条，便于验证"当前图是否已收藏"）
private val selectedImage = ImageItem(
    name = "a.png",
    path = "test-root/honkai/a.png",
    relativeUrl = "/api/media?file=%2Fa.png",
    sizeText = "1.0 KB",
    absoluteUrl = "http://h:8081/api/media?file=%2Fa.png",
)

private class FixedAddressStore(private val address: String = "http://h:8081") : ServerAddressStore {
    override fun read(): String = address
    override fun write(address: String) = Unit
}

private class MemoryAuthTokenStore(initial: StoredAuthToken? = null) : AuthTokenStore {
    private var stored: StoredAuthToken? = initial
    override fun read(): StoredAuthToken? = stored
    override fun write(token: String, expiresAtEpochMs: Long) {
        stored = StoredAuthToken(token, expiresAtEpochMs)
    }
    override fun clear() {
        stored = null
    }
}

private object NoopDownloader : ImageDownloader {
    override suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult =
        DownloadResult.Failure(DownloadFailureKind.Unknown, "测试不下载")
}

/**
 * 可编排的假传输层：分别记录"收藏写操作次数"、收到的请求体，并按需给出各种响应。
 */
private class FavoritesTransport(
    private val favoritesJson: String = "[]",
    private val uploadedJson: String = "[]",
    private val addResponse: String = """{"ok":true,"favorites":[]}""",
    private val addThrows: Boolean = false,
    private val deleteResponse: String = """{"ok":true,"favorites":[]}""",
) : HttpTransport {

    var favoriteWriteCount: Int = 0
    var favoritesGetCount: Int = 0
    var lastWriteBody: String = ""
    var lastWriteMethod: String = ""
    var lastAuthHeader: String? = null

    // 按调用次序返回不同的收藏内容：用来模拟"换服务器后另一台服务器上收藏是空的"
    val favoritesResponses: MutableList<String> = mutableListOf()

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/images") -> HttpRawResponse(200, IMAGES_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(200, STATUS_JSON, "application/json")
            // 注意：这两个返回的是裸数组，正是要验的契约
            url.endsWith("/api/favorites") -> {
                favoritesGetCount += 1
                // 有排队响应就按次序取，取完回到默认值
                val body = if (favoritesResponses.isNotEmpty()) favoritesResponses.removeAt(0) else favoritesJson
                HttpRawResponse(200, body, "application/json")
            }
            url.endsWith("/api/uploaded") -> HttpRawResponse(200, uploadedJson, "application/json")
            else -> HttpRawResponse(404, "{}", "application/json")
        }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse {
        favoriteWriteCount += 1
        lastWriteMethod = "POST"
        lastWriteBody = body
        lastAuthHeader = headers["Authorization"]
        if (addThrows) throw IllegalStateException("connect timed out")
        return HttpRawResponse(200, addResponse, "application/json")
    }

    override suspend fun delete(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse {
        favoriteWriteCount += 1
        lastWriteMethod = "DELETE"
        lastWriteBody = body
        lastAuthHeader = headers["Authorization"]
        return HttpRawResponse(200, deleteResponse, "application/json")
    }
}

private fun holder(
    transport: HttpTransport,
    loggedIn: Boolean = true,
): HomeStateHolder = HomeStateHolder(
    clientFactory = { address, onUnauthorized ->
        ApiClient(address, transport, onUnauthorized = onUnauthorized)
    },
    addressStore = FixedAddressStore(),
    imageDownloader = NoopDownloader,
    authTokenStore = MemoryAuthTokenStore(
        if (loggedIn) StoredAuthToken("tok-1", NOW_MS + 10 * ONE_HOUR_MS) else null,
    ),
    nowProvider = { NOW_MS },
)

class FavoriteFlowTest {

    @Test
    fun favoriteWhenLoggedOutOpensLoginSheetAndNeverWrites() = runBlocking {
        val transport = FavoritesTransport()
        val stateHolder = holder(transport, loggedIn = false)
        stateHolder.bootstrap()

        stateHolder.openDetail(selectedImage)
        stateHolder.toggleFavorite()

        assertTrue(stateHolder.state.loginSheetOpen, "未登录点收藏应弹登录弹层")
        assertEquals(0, transport.favoriteWriteCount, "未登录时绝不能发写请求（必然 401 且会误清登录态）")
    }

    @Test
    fun loggedInAddsFavoriteWithNormalizedSrcAndBearerToken() = runBlocking {
        val transport = FavoritesTransport(
            addResponse = """{"ok":true,"favorites":[
                {"src":"/api/media?file=%2Fa.png","name":"a.png","cat":"崩坏3"}]}""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.openDetail(selectedImage)

        stateHolder.toggleFavorite()

        assertEquals("POST", transport.lastWriteMethod)
        // 请求体里的 src 必须是规范化后的相对地址（不是 http://... 完整 URL）
        assertTrue(
            transport.lastWriteBody.contains("\"src\""),
            "请求体应带 src，实际：${transport.lastWriteBody}",
        )
        assertFalse(
            transport.lastWriteBody.contains("http://"),
            "请求体里的 src 不该是完整 URL，实际：${transport.lastWriteBody}",
        )
        assertEquals("Bearer tok-1", transport.lastAuthHeader, "写接口必须带 Bearer token")
        assertTrue(stateHolder.state.isCurrentImageFavorite, "添加后当前图应显示已收藏")
        assertEquals("已收藏", stateHolder.state.actionHint)
    }

    @Test
    fun alreadyExistsIsTreatedAsFavoritedNotAsFailure() = runBlocking {
        val transport = FavoritesTransport(
            addResponse = """{"ok":true,"alreadyExists":true,"favorites":[
                {"src":"/api/media?file=%2Fa.png","name":"a.png"}]}""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.openDetail(selectedImage)

        stateHolder.toggleFavorite()

        assertTrue(stateHolder.state.isCurrentImageFavorite, "alreadyExists 也要变成已收藏")
        assertEquals("已经在收藏里了", stateHolder.state.actionHint)
    }

    @Test
    fun removeFavoriteClearsStateAndShowsHint() = runBlocking {
        val transport = FavoritesTransport(
            favoritesJson = """[{"src":"/api/media?file=%2Fa.png","name":"a.png"}]""",
            deleteResponse = """{"ok":true,"favorites":[]}""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.openDetail(selectedImage)
        assertTrue(stateHolder.state.isCurrentImageFavorite, "前提：启动时已拉到这条收藏")

        stateHolder.toggleFavorite()

        assertEquals("DELETE", transport.lastWriteMethod)
        assertFalse(stateHolder.state.isCurrentImageFavorite, "移除后应显示未收藏")
        assertEquals("已取消收藏", stateHolder.state.actionHint)
    }

    @Test
    fun absoluteUrlInStoredFavoriteStillMatchesRelativeSelection() = runBlocking {
        // 旧数据：src 存的是完整 URL（换端口就会裂的那种）；当前选中图给的是相对路径
        val transport = FavoritesTransport(
            favoritesJson = """[{"src":"http://h:8081/api/media?file=%2Fa.png","name":"a.png"}]""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.openDetail(selectedImage)

        assertTrue(
            stateHolder.state.isCurrentImageFavorite,
            "完整 URL 与相对路径规范化后指向同一文件，必须判为已收藏",
        )
    }

    @Test
    fun duplicatedSrcInDifferentFormsIsDedupedInList() = runBlocking {
        val transport = FavoritesTransport(
            favoritesJson = """[
                {"src":"/api/media?file=%2Fa.png","name":"a.png"},
                {"src":"http://h:8081/api/media?file=%2Fa.png","name":"a.png"}]""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()

        assertEquals(1, stateHolder.state.favoriteImages.size, "同一文件的两种写法只应展示一条")
        assertEquals(1, stateHolder.state.favoriteSrcs.size)
    }

    @Test
    fun networkFailureGivesChineseHintAndKeepsUnfavorited() = runBlocking {
        val transport = FavoritesTransport(addThrows = true)
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        stateHolder.openDetail(selectedImage)

        stateHolder.toggleFavorite()

        val hint = stateHolder.state.actionHint.orEmpty()
        assertTrue(hint.contains("收藏失败"), "失败提示要说清做了什么，实际：$hint")
        assertTrue(hint.contains("无法连接"), "要带可操作原因（网络），实际：$hint")
        assertFalse(stateHolder.state.isCurrentImageFavorite)
        assertEquals("", stateHolder.state.favoriteActionSrc, "失败后必须解除按钮禁用状态")
    }

    @Test
    fun switchingViewsIsMutuallyExclusiveAndKeepsCategory() = runBlocking {
        val transport = FavoritesTransport(
            favoritesJson = """[{"src":"/api/media?file=%2Fa.png","name":"a.png"}]""",
            uploadedJson = """[{"id":"u1","src":"/uploads/u1.png","url":"/uploads/u1.png","name":"u1.png"}]""",
        )
        val stateHolder = holder(transport)
        stateHolder.bootstrap()
        assertEquals(HomeView.All, stateHolder.state.view)

        stateHolder.selectView(HomeView.Favorites)
        assertEquals(HomeView.Favorites, stateHolder.state.view)
        assertEquals(1, stateHolder.state.gridImages.size, "收藏视图的网格应来自收藏列表")
        assertEquals("收藏 · 1", stateHolder.state.contentSubtitle)

        stateHolder.selectView(HomeView.Guest)
        assertEquals(HomeView.Guest, stateHolder.state.view)
        assertEquals(1, stateHolder.state.gridImages.size, "游客视图的网格应来自上传列表")

        stateHolder.selectView(HomeView.All)
        assertEquals(HomeView.All, stateHolder.state.view)
        assertEquals("崩坏3", stateHolder.state.selectedCategory, "切回分类视图应保留原分类")
        assertEquals(1, stateHolder.state.gridImages.size)
    }

    @Test
    fun changingServerClearsFavoritesReloadsThemAndResetsView() = runBlocking {
        val transport = FavoritesTransport(
            favoritesJson = """[{"src":"/api/media?file=%2Fa.png","name":"a.png"}]""",
        )
        // 三次 GET 的应答：1) bootstrap 拉一次；2) 切到收藏视图再拉一次；3) 换服务器后拉新服务器的（空）
        // 第三次返回空，正好证明"换地址后重新拉过，而不是继续显示旧服务器的条目"
        transport.favoritesResponses.add("""[{"src":"/api/media?file=%2Fa.png","name":"a.png"}]""")
        transport.favoritesResponses.add("""[{"src":"/api/media?file=%2Fa.png","name":"a.png"}]""")
        transport.favoritesResponses.add("[]")

        val addressStore = object : ServerAddressStore {
            private var address = "http://h:8081"
            override fun read(): String = address
            override fun write(address: String) {
                this.address = address
            }
        }
        val stateHolder = HomeStateHolder(
            clientFactory = { address, onUnauthorized ->
                ApiClient(address, transport, onUnauthorized = onUnauthorized)
            },
            addressStore = addressStore,
            imageDownloader = NoopDownloader,
            authTokenStore = MemoryAuthTokenStore(StoredAuthToken("tok-1", NOW_MS + 10 * ONE_HOUR_MS)),
            nowProvider = { NOW_MS },
        )

        stateHolder.bootstrap()
        stateHolder.selectView(HomeView.Favorites)
        assertEquals(1, stateHolder.state.favoriteImages.size, "前提：第一台服务器上有一条收藏")
        val getsBeforeSwitch = transport.favoritesGetCount

        stateHolder.openSettings()
        stateHolder.updateAddressDraft("http://other:8081")
        stateHolder.saveAddress()

        assertEquals(HomeView.All, stateHolder.state.view, "换服务器后视图应回默认分类视图")
        assertEquals(
            0,
            stateHolder.state.favoriteImages.size,
            "换服务器后应显示新服务器的收藏（这里是空的），不能继续挂着旧服务器的条目",
        )
        assertTrue(
            transport.favoritesGetCount > getsBeforeSwitch,
            "换服务器后必须重新拉一次收藏，实际次数：$getsBeforeSwitch -> ${transport.favoritesGetCount}",
        )
    }

    @Test
    fun emptyStateTextDiffersPerView() = runBlocking {
        val transport = FavoritesTransport(favoritesJson = "[]", uploadedJson = "[]")
        val stateHolder = holder(transport)
        stateHolder.bootstrap()

        assertEquals("这个分类还没有图片", stateHolder.state.gridEmptyTitle)
        stateHolder.selectView(HomeView.Favorites)
        assertEquals("还没有收藏的图片", stateHolder.state.gridEmptyTitle)
        stateHolder.selectView(HomeView.Guest)
        assertEquals("还没有游客上传的图片", stateHolder.state.gridEmptyTitle)
    }
}
