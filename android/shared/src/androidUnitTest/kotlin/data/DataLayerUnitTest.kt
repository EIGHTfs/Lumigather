// 数据层的离线单测：不连网的纯逻辑覆盖。
//
// 为什么要这几组：数据层出过的（以及最容易出的）错，全都是"不报错的错"——
// - root 参数没传对 → 8 个分类返回同一批图，接口全 200；
// - 相对地址没拼 → 图片全白，请求本身不失败；
// - 百分号编码漏了 → 含空格/中文的路径直接 404 或者被当成别的目录；
// - returned/limit 判错 → 无限加载空页。
// 这些都没法靠"跑起来看一眼"发现，只能把它们写成断言。

package com.eightfs.lumigather.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 假的传输层：把"发出去的请求"和"要回的响应"都变成可断言的变量。
// 有了它，ApiClient 的四种失败分支都能在毫秒级覆盖，不必真去构造 500 或坏 JSON。
private class FakeTransport(
    private val response: HttpRawResponse? = null,
    private val failure: Throwable? = null
) : HttpTransport {

    // 记录最后一次请求，供断言"参数名/编码对不对"。
    var lastUrl: String = ""
    var lastQuery: Map<String, String> = emptyMap()

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse {
        lastUrl = url
        lastQuery = query
        failure?.let { throw it }
        return response ?: HttpRawResponse(200, "{}", null)
    }
}

private fun okJson(body: String, contentType: String = "application/json"): FakeTransport =
    FakeTransport(HttpRawResponse(200, body, contentType))

class UrlBuilderTest {

    @Test
    fun percentEncodeKeepsUnreservedCharsUnchanged() {
        assertEquals("aZ09-._~", UrlBuilder.percentEncode("aZ09-._~"))
    }

    @Test
    fun percentEncodeEncodesSpaceAndUtf8Path() {
        // 这条路径就是实测里 原神 分类的 root 末段：空格 + en dash(–) + 中文。
        val encoded = UrlBuilder.percentEncode("Genshin Impact – 原神")
        assertEquals("Genshin%20Impact%20%E2%80%93%20%E5%8E%9F%E7%A5%9E", encoded)
    }

    @Test
    fun percentEncodeEncodesReservedChars() {
        // / [ ] ( ) 都属于 reserved 或 gen-delims，必须编码，否则服务端可能截断路径。
        assertEquals("%2F%5B%5D%28%29", UrlBuilder.percentEncode("/[]()"))
    }

    @Test
    fun buildAppendsQueryInDeclarationOrder() {
        val url = UrlBuilder.build(
            "http://h:8081/api/images",
            mapOf("root" to "/a b", "offset" to "0", "limit" to "20")
        )
        assertEquals("http://h:8081/api/images?root=%2Fa%20b&offset=0&limit=20", url)
    }

    @Test
    fun buildWithoutQueryKeepsUrlClean() {
        assertEquals("http://h:8081/api/status", UrlBuilder.build("http://h:8081/api/status", emptyMap()))
    }
}

class MediaUrlTest {

    @Test
    fun normalizeBaseUrlDropsTrailingSlashAndSpaces() {
        assertEquals("http://h:8081", normalizeBaseUrl("  http://h:8081/  "))
        assertEquals("", normalizeBaseUrl("   "))
    }

    @Test
    fun joinUrlKeepsSubPathPrefix() {
        // 服务端部署在子路径下时，前缀必须保留，不能被当成多余部分去掉。
        assertEquals(
            "https://host/lumigather/api/media?file=%2Fa.png",
            joinUrl("https://host/lumigather", "/api/media?file=%2Fa.png")
        )
    }

    @Test
    fun joinUrlDoesNotDoubleEncode() {
        // 关键：已编码的 %2F 必须原样保留，重编会变成 %252F 从而 404。
        val joined = joinUrl("http://h:8081/", "/api/media?file=%2Fa%2Fb.png")
        assertEquals("http://h:8081/api/media?file=%2Fa%2Fb.png", joined)
        assertFalse(joined.contains("%252F"))
    }

    @Test
    fun joinUrlPassesAbsoluteThrough() {
        assertEquals("https://cdn/x.png", joinUrl("http://h:8081", "https://cdn/x.png"))
    }

    @Test
    fun joinUrlWithEmptyInputsReturnsEmpty() {
        assertEquals("", joinUrl("http://h:8081", ""))
    }

    @Test
    fun mediaUrlOfUsesItemRelativeUrl() {
        val item = ImageItem(name = "a.png", relativeUrl = "/api/media?file=%2Fa.png")
        assertEquals("http://h:8081/api/media?file=%2Fa.png", mediaUrlOf("http://h:8081", item))
    }
}

class PagedImagesTest {

    @Test
    fun fullPageBeforeTotalHasMore() {
        val page = PagedImages(total = 100, offset = 0, limit = 20, returned = 20)
        assertFalse(page.isEnd)
        assertTrue(page.hasMore)
        assertEquals(20, page.nextOffset)
    }

    @Test
    fun shortPageIsEnd() {
        // returned < limit 一律视为到底，哪怕 total 说还有（服务端过滤掉不支持的类型时会这样）。
        val page = PagedImages(total = 100, offset = 80, limit = 20, returned = 5)
        assertTrue(page.isEnd)
        assertFalse(page.hasMore)
    }

    @Test
    fun cursorReachingTotalIsEnd() {
        val page = PagedImages(total = 40, offset = 20, limit = 20, returned = 20)
        assertTrue(page.isEnd)
    }

    @Test
    fun outOfRangeOffsetIsEnd() {
        // 实测：offset 越界时 returned=0、total 仍是真实值。不能因为 total>offset 就继续请求。
        val page = PagedImages(total = 1262, offset = 999999, limit = 5, returned = 0)
        assertTrue(page.isEnd)
    }

    @Test
    fun degradedResponseIsEnd() {
        // 降级响应缺字段：解析后 total/returned 都是 0，应判为"到底了"，UI 显示空态。
        val page = PagedImages()
        assertTrue(page.isEnd)
    }
}

class ApiClientTest {

    @Test
    fun blankBaseUrlIsInvalidConfig() = runBlocking {
        val client = ApiClient("   ", FakeTransport())
        val result = client.fetchStatus()
        assertTrue(result is ApiResult.Err)
        assertTrue((result as ApiResult.Err).error is ApiError.InvalidConfig)
    }

    @Test
    fun blankRootIsBadArgumentAndSendsNoRequest() = runBlocking {
        val transport = FakeTransport(HttpRawResponse(200, "{}", null))
        val client = ApiClient("http://h:8081", transport)
        val result = client.fetchImages(root = "")
        assertTrue((result as ApiResult.Err).error is ApiError.BadArgument)
        // 没有发请求：空 root 一旦发出去，后端会回落到全局 fsRoot，拿回来的是别的目录的图。
        assertEquals("", transport.lastUrl)
    }

    @Test
    fun imagesRequestUsesRootParamAndRecursiveTrue() = runBlocking {
        val transport = okJson("""{"total":3,"returned":0,"images":[]}""")
        val client = ApiClient("http://h:8081/", transport)
        client.fetchImages(root = "/a b", offset = 40, limit = 20)
        assertEquals("http://h:8081/api/images", transport.lastUrl)
        // 参数名必须是 root（不是 dir），递归必须是 true（默认 false 只拿根目录那一层）。
        assertEquals("/a b", transport.lastQuery["root"])
        assertEquals("true", transport.lastQuery["recursive"])
        assertEquals("40", transport.lastQuery["offset"])
        assertEquals("20", transport.lastQuery["limit"])
        assertNull(transport.lastQuery["dir"])
    }

    @Test
    fun negativeOffsetAndZeroLimitAreClamped() = runBlocking {
        val transport = okJson("""{"images":[]}""")
        val client = ApiClient("http://h:8081", transport)
        client.fetchImages(root = "/a", offset = -5, limit = 0)
        assertEquals("0", transport.lastQuery["offset"])
        assertEquals("1", transport.lastQuery["limit"])
    }

    @Test
    fun httpErrorCarriesStatusCodeAndServerMessage() = runBlocking {
        val transport = FakeTransport(
            HttpRawResponse(500, """{"error":"ENOENT: no such file or directory"}""", null)
        )
        val result = ApiClient("http://h:8081", transport).fetchImages(root = "/nope")
        val error = (result as ApiResult.Err).error
        assertTrue(error is ApiError.Http)
        assertEquals(500, (error as ApiError.Http).statusCode)
        assertEquals("ENOENT: no such file or directory", error.serverMessage)
    }

    @Test
    fun httpErrorWithNonJsonBodyStillReportsStatus() = runBlocking {
        val transport = FakeTransport(HttpRawResponse(502, "<html>bad gateway</html>", "text/html"))
        val result = ApiClient("http://h:8081", transport).fetchStatus()
        val error = (result as ApiResult.Err).error
        assertEquals(502, (error as ApiError.Http).statusCode)
        assertEquals("", error.serverMessage)
    }

    @Test
    fun errorFieldInsideOkResponseIsTreatedAsFailure() = runBlocking {
        // 后端存在 200 + {"error": ...} 的路径。当成成功会把失败显示成"空目录"。
        val transport = okJson("""{"error":"EACCES: permission denied"}""")
        val result = ApiClient("http://h:8081", transport).fetchDirectories("/x")
        val error = (result as ApiResult.Err).error
        assertTrue(error is ApiError.Http)
        assertEquals("EACCES: permission denied", (error as ApiError.Http).serverMessage)
    }

    @Test
    fun malformedJsonBecomesParseError() = runBlocking {
        val transport = okJson("{not json")
        val result = ApiClient("http://h:8081", transport).fetchStatus()
        assertTrue((result as ApiResult.Err).error is ApiError.Parse)
    }

    @Test
    fun networkFailureBecomesNetworkError() = runBlocking {
        val transport = FakeTransport(failure = IllegalStateException("connect timed out"))
        val result = ApiClient("http://h:8081", transport).fetchStatus()
        val error = (result as ApiResult.Err).error
        assertTrue(error is ApiError.Network)
        assertEquals("connect timed out", error.detail)
        // cause 保留原始异常，便于日志追栈；但 userMessage 里不能出现技术细节。
        assertTrue(error.cause is IllegalStateException)
        assertFalse(error.userMessage.contains("timed out"))
    }

    @Test
    fun degradedImagesResponseParsesAsEmptyPage() = runBlocking {
        // 实测降级体：缺 root/total/offset/limit/returned/recursive。
        val transport = okJson("""{"path":"test-root/__nope__","count":0,"images":[]}""")
        val result = ApiClient("http://h:8081", transport).fetchImages(root = "test-root/__nope__")
        val page = (result as ApiResult.Ok).value
        assertEquals(0, page.returned)
        assertEquals(0, page.images.size)
        assertTrue(page.isEnd)
    }

    @Test
    fun statusResponseParses() = runBlocking {
        val transport = okJson(
            """{"ok":true,"needsSetup":false,"needsAuth":true,"authed":false,""" +
                """"port":8081,"uptimeSec":630,"version":"1.0.3"}"""
        )
        val status = (ApiClient("http://h:8081", transport).fetchStatus() as ApiResult.Ok).value
        assertTrue(status.ok)
        assertTrue(status.needsAuth)
        assertEquals(8081, status.port)
        assertEquals(630L, status.uptimeSec)
        assertEquals("1.0.3", status.version)
    }
}

class RepositoryTest {

    // 一份贴合真实结构的站点配置：两个分类，其中一个在 directories 里没有路径（脏数据）。
    private val siteConfigJson = """
        {"title":"拾光集","fsRoot":"test-root/fallback","imagesPerRow":20,
         "categories":[{"name":"崩坏3","label":"崩坏3","color":"#c084fc"},
                       {"name":"原神","label":"原神","color":"#4ade80"}],
         "directories":[{"path":"test-root/honkai","category":"崩坏3"}]}
    """.trimIndent()

    @Test
    fun categoriesBindTheirOwnRootPath() = runBlocking {
        val repository = LumigatherRepository(ApiClient("http://h:8081", okJson(siteConfigJson)))
        val views = (repository.loadCategories() as ApiResult.Ok).value
        assertEquals(2, views.size)
        // 契约字段：name / color / rootPath / imageCount 必须齐。
        assertEquals("test-root/honkai", views[0].rootPath)
        assertEquals("#c084fc", views[0].color)
        assertEquals(0, views[0].imageCount)
        assertEquals(CountState.UNKNOWN, views[0].countState)
        assertFalse(views[0].rootIsFallback)
    }

    @Test
    fun categoryWithoutDirectoryFallsBackAndIsFlagged() = runBlocking {
        val repository = LumigatherRepository(ApiClient("http://h:8081", okJson(siteConfigJson)))
        val views = (repository.loadCategories() as ApiResult.Ok).value
        // 没配路径的分类回落全局根，但必须打上标记 —— 否则用户会以为两个分类是同一种图库。
        assertEquals("test-root/fallback", views[1].rootPath)
        assertTrue(views[1].rootIsFallback)
    }

    @Test
    fun refreshCategoryCountReadsTotalFromProbeRequest() = runBlocking {
        // 这个假传输按调用顺序回两种响应：先站点配置，再张数探针。
        val transport = SequencedTransport(
            HttpRawResponse(200, siteConfigJson, "application/json"),
            HttpRawResponse(200, """{"total":72651,"returned":1,"images":[]}""", "application/json")
        )
        val repository = LumigatherRepository(ApiClient("http://h:8081", transport))
        repository.loadCategories()
        val updated = (repository.refreshCategoryCount("崩坏3") as ApiResult.Ok).value
        assertEquals(72651, updated.imageCount)
        assertEquals(CountState.READY, updated.countState)
        // 探针请求必须带该分类自己的 root，且 limit=1 只读 total。
        assertEquals("test-root/honkai", transport.lastQuery["root"])
        assertEquals("1", transport.lastQuery["limit"])
        // 仓库缓存里的同名分类也要同步更新，供 UI 直接读。
        assertEquals(72651, repository.findCategory("崩坏3")?.imageCount)
    }

    @Test
    fun loadPageFillsAbsoluteUrl() = runBlocking {
        val pageJson = """
            {"path":"test-root/honkai","root":"test-root/honkai","recursive":true,"count":1,"total":1,
             "offset":0,"limit":20,"returned":1,
             "images":[{"name":"a.png","path":"test-root/honkai/a.png",
                        "url":"/api/media?file=%2Froot%2Fhonkai%2Fa.png",
                        "size":"139.4 KB","modified":"2023-08-18T06:56:03.097Z"}]}
        """.trimIndent()
        val repository = LumigatherRepository(ApiClient("http://h:8081", okJson(pageJson)))
        val page = (repository.loadPage("test-root/honkai", offset = 0) as ApiResult.Ok).value
        val image = page.images.single()
        // UI 拿到的必须是可直接加载的绝对地址，且不能是双重编码。
        assertEquals("http://h:8081/api/media?file=%2Froot%2Fhonkai%2Fa.png", image.absoluteUrl)
        assertEquals("PNG", image.extensionText)
        assertEquals("2023-08-18", image.modifiedDateText)
    }

    @Test
    fun unknownCategoryIsRejectedWithoutRequest() = runBlocking {
        val transport = okJson(siteConfigJson)
        val repository = LumigatherRepository(ApiClient("http://h:8081", transport))
        val result = repository.loadPageForCategory("不存在的分类", offset = 0)
        assertTrue((result as ApiResult.Err).error is ApiError.BadArgument)
        assertEquals("", transport.lastUrl)
    }

    @Test
    fun refreshCategoryCountsKeepsEveryCategoryWhenRunConcurrently() = runBlocking {
        // 回归用例：并发补张数时，如果每个协程各自「读缓存 → 改 → 写回」，
        // 后写的会覆盖先写的，部分分类张数停留在 0。这条断言就是钉住那个丢更新。
        val countByRoot = mapOf("test-root/c1" to 11, "test-root/c2" to 22, "test-root/c3" to 33, "test-root/c4" to 44)
        val config = """
            {"title":"t","fsRoot":"test-root/fallback","imagesPerRow":20,
             "categories":[{"name":"c1","label":"c1","color":"#111"},
                           {"name":"c2","label":"c2","color":"#222"},
                           {"name":"c3","label":"c3","color":"#333"},
                           {"name":"c4","label":"c4","color":"#444"}],
             "directories":[{"path":"test-root/c1","category":"c1"},
                            {"path":"test-root/c2","category":"c2"},
                            {"path":"test-root/c3","category":"c3"},
                            {"path":"test-root/c4","category":"c4"}]}
        """.trimIndent()

        // 按 root 路由而不是按调用顺序：并发下响应顺序不确定，顺序化假传输会串数据。
        val transport = RoutingTransport { query ->
            val root = query["root"].orEmpty()
            if (root.isEmpty()) {
                HttpRawResponse(200, config, "application/json")
            } else {
                HttpRawResponse(200, """{"total":${countByRoot.getValue(root)},"returned":1,"images":[]}""", "application/json")
            }
        }

        val repository = LumigatherRepository(ApiClient("http://h:8081", transport))
        repository.loadCategories()
        val updated = repository.refreshCategoryCounts(maxConcurrent = 4)

        // 四个分类各自的张数都在，且没有互相覆盖。
        assertEquals(listOf(11, 22, 33, 44), updated.map { it.imageCount })
        assertEquals(4, repository.categories().count { it.countState == CountState.READY })
    }
}

// 顺序响应传输层：按调用次序依次吐出预置响应，用于"先配置、再探针"这类多次请求的用例。
private class SequencedTransport(vararg responses: HttpRawResponse) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    var lastQuery: Map<String, String> = emptyMap()

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse {
        lastQuery = query
        return queue.removeFirst()
    }
}

// 按请求内容路由的传输层：并发场景下调用顺序不确定，只能靠"请求了什么参数"来决定回什么。
private class RoutingTransport(
    private val route: (Map<String, String>) -> HttpRawResponse
) : HttpTransport {
    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        route(query)
}
