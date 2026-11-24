// 详情栏动作（下载 / 收藏占位）的状态机测试。
//
// 为什么值得测：这两个动作的"对"不在于能不能编译，而在于三种用户可感知的结果：
// 存成功了、相册里本来就有、失败时给的是哪一类原因。真机上反复试这四种分支成本很高
// （要清相册、断网、拒权限），用假下载器把状态机钉死，真机只验"图片真的进了相册"。
//
// 同时钉死一条产品约束：收藏是占位，**不允许**产生任何"已收藏"的状态。

package ui.screens.download

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.ImageItem
import kotlinx.coroutines.runBlocking
import ui.screens.settings.ServerAddressStore
import ui.screens.state.DownloadUiState
import ui.screens.state.HomeStateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 记录调用次的假下载器：用它断言"点一次只下一次""点收藏不会触发下载"。
private class FakeImageDownloader(
    private val result: DownloadResult,
) : ImageDownloader {

    var callCount: Int = 0
    val requestedUrls = mutableListOf<String>()

    override suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult {
        callCount += 1
        requestedUrls.add(imageUrl)
        return result
    }
}

// 空实现存储：这些用例不涉及地址读写
private class EmptyAddressStore : ServerAddressStore {
    override fun read(): String = "http://h:8081"
    override fun write(address: String) = Unit
}

// 不会被调用到的传输层（下载走的是 ImageDownloader，不走 ApiClient）
private object UnusedTransport : HttpTransport {
    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        HttpRawResponse(500, "{}", "application/json")
}

private val sampleImage = ImageItem(
    name = "Broyna.webp",
    path = "/volume6/a/Broyna.webp",
    relativeUrl = "/api/media?file=%2Fvolume6%2Fa%2FBroyna.webp",
    sizeText = "137.2 KB",
    modifiedIso = "2024-04-21T10:00:00Z",
    absoluteUrl = "http://h:8081/api/media?file=%2Fvolume6%2Fa%2FBroyna.webp",
)

private fun holderWith(downloader: ImageDownloader): HomeStateHolder =
    HomeStateHolder(
        clientFactory = { address -> ApiClient(address, UnusedTransport) },
        addressStore = EmptyAddressStore(),
        imageDownloader = downloader,
    )

class DetailActionsTest {

    @Test
    fun downloadSuccessReportsSavedAndPassesAbsoluteUrl() = runBlocking {
        val downloader = FakeImageDownloader(DownloadResult.Success)
        val holder = holderWith(downloader)
        holder.openDetail(sampleImage)

        holder.downloadSelectedImage()

        val state = holder.state.downloadState
        assertTrue(state is DownloadUiState.Done, "成功应落到 Done，实际 $state")
        assertEquals("已保存到相册", (state as DownloadUiState.Done).message)
        // 传给下载器的必须是已物化的绝对地址，而不是相对路径
        assertEquals(listOf(sampleImage.absoluteUrl), downloader.requestedUrls)
    }

    @Test
    fun alreadySavedIsReportedAsDoneWithoutClaimingANewCopy() = runBlocking {
        val holder = holderWith(FakeImageDownloader(DownloadResult.AlreadySaved))
        holder.openDetail(sampleImage)

        holder.downloadSelectedImage()

        val state = holder.state.downloadState
        assertTrue(state is DownloadUiState.Done)
        // 措辞必须让用户明白"没有再存一份"，而不是又提示一次"已保存"
        assertTrue((state as DownloadUiState.Done).message.contains("未重复保存"))
    }

    @Test
    fun permissionDeniedGivesActionableChineseMessage() = runBlocking {
        val failure = DownloadResult.Failure(
            DownloadFailureKind.PermissionDenied,
            "保存到相册需要「存储」权限。请在系统设置 → 应用 → 拾光集 → 权限里允许后再试",
        )
        val holder = holderWith(FakeImageDownloader(failure))
        holder.openDetail(sampleImage)

        holder.downloadSelectedImage()

        val state = holder.state.downloadState
        assertTrue(state is DownloadUiState.Failed)
        assertTrue(
            (state as DownloadUiState.Failed).message.contains("权限"),
            "权限失败必须说清是权限问题并能指导用户，实际：${state.message}",
        )
    }

    @Test
    fun networkFailureIsReportedAsFailureNotSuccess() = runBlocking {
        val failure = DownloadResult.Failure(DownloadFailureKind.Network, "下载失败，请检查网络或服务器是否可访问")
        val holder = holderWith(FakeImageDownloader(failure))
        holder.openDetail(sampleImage)

        holder.downloadSelectedImage()

        val state = holder.state.downloadState
        assertTrue(state is DownloadUiState.Failed)
        assertTrue((state as DownloadUiState.Failed).message.contains("网络"))
    }

    @Test
    fun switchingImageResetsPreviousDownloadResult() = runBlocking {
        val holder = holderWith(FakeImageDownloader(DownloadResult.Success))
        holder.openDetail(sampleImage)
        holder.downloadSelectedImage()

        // 换一张图：上一张的"已保存到相册"不能跟着显示到新图上
        holder.openDetail(sampleImage.copy(name = "other.png", absoluteUrl = "http://h:8081/api/media?file=other"))

        assertEquals(DownloadUiState.Idle, holder.state.downloadState)
        assertNull(holder.state.actionHint)
    }

    @Test
    fun favoriteOnlyShowsHintAndNeverTouchesDownloadOrSavedState() = runBlocking {
        val downloader = FakeImageDownloader(DownloadResult.Success)
        val holder = holderWith(downloader)
        holder.openDetail(sampleImage)

        holder.requestFavorite()

        val hint = holder.state.actionHint
        assertTrue(hint != null && hint.contains("登录"), "收藏应提示需要登录，实际：$hint")
        // 关键约束：收藏占位不能产生任何"已收藏"痕迹，也不能顺带触发下载
        assertEquals(0, downloader.callCount)
        assertEquals(DownloadUiState.Idle, holder.state.downloadState)
    }
}
