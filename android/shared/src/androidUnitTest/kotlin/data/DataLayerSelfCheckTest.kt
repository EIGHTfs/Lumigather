// 真机/真服务自检：连真实部署实例，把"分类 → 各自张数 → 第一张图绝对地址"整条链路跑一遍。
//
// 这是 task-3 的验收入口。它必须打真实地址，因为要在这一步抓的就是两类"接口不报错但结果错"的问题：
// 1) 张数全都一样 —— 说明请求没带该分类自己的 root（后端回落到了全局 fsRoot）；
// 2) 图片地址拼错 —— 说明相对地址没拼 baseUrl，或者 file 参数被二次编码。
//
// 跑法（需要能访问该地址）：
//   gradlew :shared:testDebugUnitTest --tests "*DataLayerSelfCheckTest*"
// 换地址：-Dlumigather.selfcheck.baseUrl=http://其它地址:端口
// 或者环境变量 LUMIGATHER_SELF_CHECK_BASE_URL

package com.eightfs.lumigather.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 刻意不设默认地址：这是个「连真实部署实例」的自检，写死任何一台机器的 IP 都会让
// 别人克隆后跑测试时去连一个不存在的地址（超时/失败），而代码本身没问题。
// 没配置就跳过（JUnit 的 assumeTrue），报告里会明确显示 skipped。
private const val BASE_URL_PROPERTY = "lumigather.selfcheck.baseUrl"
private const val BASE_URL_ENV = "LUMIGATHER_SELF_CHECK_BASE_URL"

// 站点配置里配了 8 个游戏分类。数量对不上说明配置变了或接口契约变了，都应当让自检红。
private const val EXPECTED_CATEGORY_COUNT = 8

// 媒体响应的 Content-Type 前缀。图片详情与后续收藏功能都依赖它。
private const val IMAGE_CONTENT_TYPE_PREFIX = "image/"

private const val HTTP_STATUS_OK = 200

class DataLayerSelfCheckTest {

    @Test
    fun selfCheckAgainstRealService() = runBlocking {
        // 这个自检必须打真实部署实例才有意义；没有配置就从这条链路里退出，
        // 而不是去连一个默认地址。assumeTrue 让报告显示 skipped 而非 passed，
        // 避免「以为验过了」。
        val configuredBaseUrl = resolveBaseUrl()
        Assume.assumeTrue(
            "未配置 $BASE_URL_PROPERTY 或 $BASE_URL_ENV，跳过真实服务自检",
            configuredBaseUrl != null
        )
        val baseUrl = requireNotNull(configuredBaseUrl)
        val api = ApiClient(baseUrl, OkHttpHttpTransport())
        val repository = LumigatherRepository(api)

        println("========== 拾光集数据层自检 ==========")
        println("服务端      : $baseUrl")

        // 第一步：在线状态。顺便验证 status 的解析（底部状态栏依赖它）。
        when (val statusResult = repository.loadStatus()) {
            is ApiResult.Ok -> {
                val status = statusResult.value
                println("状态        : ok=${status.ok} version=${status.version} port=${status.port} uptime=${status.uptimeSec}s")
            }
            is ApiResult.Err ->
                throw AssertionError("连不上服务端：${statusResult.error.userMessage} / ${describe(statusResult.error)}")
        }

        // 第二步：分类列表（含每个分类自己的绝对路径）。
        val categories = when (val result = repository.loadCategories()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> throw AssertionError("拉分类失败：${describe(result.error)}")
        }
        assertEquals(EXPECTED_CATEGORY_COUNT, categories.size, "分类数量与站点配置不符")
        println("分类        : ${categories.size} 个")

        // 第三步：批量补张数。走的是生产路径（并发 3、探测后单点合并缓存），
        // 因此这一步同时验证了并发补数不会互相覆盖。
        val countStartedAtMs = System.currentTimeMillis()
        val updatedViews = repository.refreshCategoryCounts(maxConcurrent = DEFAULT_COUNT_CONCURRENCY)
        val elapsedTotalMs = System.currentTimeMillis() - countStartedAtMs
        assertEquals(categories.size, updatedViews.size, "批量补张数后分类数量变了")
        updatedViews.forEach { updated ->
            println("  ${updated.name}  count=${updated.imageCount}  root=${updated.rootPath}")
        }
        println("张数合计    : ${updatedViews.sumOf { it.imageCount }} 张，${updatedViews.size} 次扫盘共 ${elapsedTotalMs}ms")

        // 关键断言：8 个张数必须各不相同。全相同 = root 没传对，是最容易发生也最难发现的错。
        val distinctCounts = updatedViews.map { it.imageCount }.distinct()
        assertEquals(
            updatedViews.size, distinctCounts.size,
            "分类张数出现重复（$distinctCounts），说明请求没带各分类自己的 root，后端回落到了全局 fsRoot"
        )

        // 第四步：拿第一个分类的第一页，验证图片绝对地址可直接加载。
        val firstCategory = categories.first()
        val firstPage = when (val result = repository.loadPage(firstCategory.rootPath, offset = 0, limit = 1)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> throw AssertionError("取 ${firstCategory.name} 第一页失败：${describe(result.error)}")
        }
        val firstImage = firstPage.images.firstOrNull()
            ?: throw AssertionError("${firstCategory.name} 第一页没有图片，无法验证媒体地址")
        println("第一张图    : ${firstImage.name} (${firstImage.sizeText}, ${firstImage.extensionText}, ${firstImage.modifiedDateText})")
        println("媒体地址    : ${firstImage.absoluteUrl}")

        // 相对地址必须被拼成绝对地址；没拼的话这里会看到 /api/media 开头。
        assertTrue(
            firstImage.absoluteUrl.startsWith(baseUrl),
            "媒体地址没有拼上 baseUrl：${firstImage.absoluteUrl}"
        )
        // 二次编码会让 %2F 变成 %252F，服务端直接 404。
        assertTrue(
            !firstImage.absoluteUrl.contains("%252F"),
            "媒体地址出现二次编码：${firstImage.absoluteUrl}"
        )

        // 第五步：真去取一次这张图，验证 200 + image/*（对齐验收第 3 条，不靠肉眼）。
        // 单独建一个传输层：媒体地址不在 /api 接口表里，不该为它往 ApiClient 上加口子。
        val mediaResponse = OkHttpHttpTransport().get(
            firstImage.absoluteUrl, emptyMap(), DEFAULT_REQUEST_TIMEOUT_MS
        )
        println("媒体响应    : HTTP ${mediaResponse.statusCode}, Content-Type=${mediaResponse.contentType}")
        assertEquals(HTTP_STATUS_OK, mediaResponse.statusCode, "媒体地址取图失败")
        assertTrue(
            mediaResponse.contentType.orEmpty().startsWith(IMAGE_CONTENT_TYPE_PREFIX),
            "媒体响应 Content-Type 不是图片：${mediaResponse.contentType}"
        )
        println("=====================================")
    }

    /** 没配置时返回 null —— 调用方据此跳过，而不是回落到某个写死的地址。 */
    private fun resolveBaseUrl(): String? {
        val fromProperty = System.getProperty(BASE_URL_PROPERTY)
        if (!fromProperty.isNullOrBlank()) return fromProperty
        val fromEnv = System.getenv(BASE_URL_ENV)
        if (!fromEnv.isNullOrBlank()) return fromEnv
        return null
    }

    // 把错误展开成一行：userMessage 给用户看，detail/状态码给排查用，自检里两者都要。
    private fun describe(error: ApiError): String = when (error) {
        is ApiError.Network -> "Network(${error.detail})"
        is ApiError.Http -> "Http(${error.statusCode}, ${error.serverMessage})"
        is ApiError.Parse -> "Parse(${error.detail})"
        is ApiError.InvalidConfig -> "InvalidConfig(${error.detail})"
        is ApiError.BadArgument -> "BadArgument(${error.detail})"
    }
}
