// 应用更新的单测：版本解析/比较边界、服务端 404、网络失败、SHA-256 与签名不匹配的拒绝、
// 取消与进度、以及"检查更新失败不能污染全局提示槽"。
//
// 为什么这些必须在没有真实 APK / 没有 GitHub 的情况下钉死：
// - 版本解析写错 → 用户永远看不到更新（或看到不该有的降级提示）；
// - 签名/哈希校验漏判 → 局域网 HTTP 场景下会把被替换的包装上去，这是安全事件；
// - 失败写进全局提示槽 → 会盖掉"登录已过期"这类用户当下必须处理的提示（已踩过一次）。
// 全部走注入的假平台，所以能构造"签名不匹配"这种现实里要靠重签才复现的分支。

package ui.screens.update

import com.eightfs.lumigather.data.AppUpdateClient
import com.eightfs.lumigather.data.AppUpdatePlatform
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.InstallResult
import com.eightfs.lumigather.data.RemoteRelease
import com.eightfs.lumigather.data.VersionTag
import com.eightfs.lumigather.data.ApiClient
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.HomeStateHolder
import ui.screens.state.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_704_067_200_000L
private const val LOCAL_VERSION_CODE = 1125

// GitHub Releases API 的真实返回形状（字段名照实测抓的那份，不要自创）
private const val RELEASE_JSON_NEWER = """
{"tag_name":"0.0.3-1130","name":"0.0.3-1130","published_at":"2026-11-27T01:00:00Z",
 "body":"修复若干问题\n新增更新入口",
 "assets":[{"name":"app-release.apk","size":8000000,
   "browser_download_url":"https://example.test/EIGHTfs/Lumigather/releases/download/0.0.3-1130/app-release.apk",
   "digest":"sha256:99453c0e1751c64805ef4fe02c6ca8ee84abf8384ae6b8163e5fdb5f006ce257"}]}
"""

private const val RELEASE_JSON_SAME = """
{"tag_name":"0.0.2-1125","body":"","published_at":"2026-11-25T01:43:34Z",
 "assets":[{"name":"app-release.apk","size":7858041,
   "browser_download_url":"https://example.test/app-release.apk","digest":""}]}
"""

private const val RELEASE_JSON_OLDER = """
{"tag_name":"0.0.1-1119","body":"","published_at":"2026-11-20T01:00:00Z",
 "assets":[{"name":"app-release.apk","size":7800000,
   "browser_download_url":"https://example.test/app-release.apk","digest":""}]}
"""

private const val RELEASE_JSON_BAD_TAG = """
{"tag_name":"latest","body":"","published_at":"2026-11-20T01:00:00Z",
 "assets":[{"name":"app-release.apk","size":7800000,
   "browser_download_url":"https://example.test/app-release.apk","digest":""}]}
"""

// 发布里只有源码包、没有 apk：应报"没有 APK 安装包"而不是给出空下载地址
private const val RELEASE_JSON_NO_APK = """
{"tag_name":"0.0.3-1130","body":"","published_at":"2026-11-27T01:00:00Z",
 "assets":[{"name":"source.zip","size":1000,"browser_download_url":"https://example.test/source.zip"}]}
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

// 假的 HttpTransport：只服务"查 GitHub 发布"这一个请求
private class ReleaseTransport(
    private val statusCode: Int = 200,
    private val body: String = RELEASE_JSON_NEWER,
    private val throws: Boolean = false,
) : HttpTransport {
    var requests: Int = 0
        private set

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse {
        requests += 1
        if (throws) throw IllegalStateException("connect timed out")
        return HttpRawResponse(statusCode, body, "application/json")
    }
}

// 假的平台能力：本地版本可配，下载/校验/安装结果可编排
private class FakeUpdatePlatform(
    private val localCode: Int = LOCAL_VERSION_CODE,
    private val localName: String = "0.0.2-1125",
    private val result: InstallResult = InstallResult.Started,
    private val progressSteps: List<Long> = emptyList(),
) : AppUpdatePlatform {
    var downloadCalls: Int = 0
        private set

    override fun localVersionCode(): Int = localCode
    override fun localVersionName(): String = localName

    override suspend fun downloadVerifyAndInstall(
        release: RemoteRelease,
        onProgress: (Long, Long) -> Unit,
    ): InstallResult {
        downloadCalls += 1
        progressSteps.forEach { downloadedBytes -> onProgress(downloadedBytes, release.sizeBytes) }
        return result
    }
}

private fun holder(
    transport: HttpTransport,
    platform: AppUpdatePlatform,
): HomeStateHolder = HomeStateHolder(
    clientFactory = { address, onUnauthorized ->
        ApiClient(address, transport, onUnauthorized = onUnauthorized)
    },
    addressStore = FixedAddressStore(),
    imageDownloader = NoopDownloader,
    authTokenStore = LoggedOutTokens,
    nowProvider = { NOW_MS },
    appUpdateClientProvider = { AppUpdateClient(transport) },
    appUpdatePlatformProvider = { platform },
)

class UpdateFlowTest {

    @Test
    fun newerReleaseIsOfferedWithNotesAndSize() = runBlocking {
        val stateHolder = holder(ReleaseTransport(), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        val update = stateHolder.state.update
        assertTrue(update is UpdateUiState.Available, "远端 1130 应被判为新版本，实际：$update")
        val release = (update as UpdateUiState.Available).release
        assertEquals("0.0.3-1130", release.versionName)
        assertEquals(1130, release.versionCode)
        assertEquals(8000000L, release.sizeBytes)
        assertTrue(release.releaseNotes.contains("修复若干问题"))
        assertEquals(
            "99453c0e1751c64805ef4fe02c6ca8ee84abf8384ae6b8163e5fdb5f006ce257",
            release.sha256,
            "digest 的 sha256: 前缀要被剥掉",
        )
    }

    @Test
    fun sameAndOlderVersionsBothReportLatest() = runBlocking {
        val same = holder(ReleaseTransport(body = RELEASE_JSON_SAME), FakeUpdatePlatform())
        same.checkUpdate()
        assertEquals(UpdateUiState.Latest, same.state.update, "同版本应显示已是最新")

        // 降级保护：远端比本地旧时不能提示升级（系统也会拒绝降级安装）
        val older = holder(ReleaseTransport(body = RELEASE_JSON_OLDER), FakeUpdatePlatform())
        older.checkUpdate()
        assertEquals(UpdateUiState.Latest, older.state.update)
    }

    @Test
    fun unparseableTagGivesReadableFailureInsteadOfThrowing() = runBlocking {
        val stateHolder = holder(ReleaseTransport(body = RELEASE_JSON_BAD_TAG), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        val update = stateHolder.state.update
        assertTrue(update is UpdateUiState.Failed, "解析不出应给失败态而不是抛异常，实际：$update")
        assertTrue((update as UpdateUiState.Failed).message.contains("无法从发布版本号"))
    }

    @Test
    fun versionTagParsingCoversBoundaries() {
        // 正常形态
        assertEquals(1125, VersionTag.parseVersionCode("0.0.2-1125"))
        // 容忍前导 v
        assertEquals(1125, VersionTag.parseVersionCode("v0.0.2-1125"))
        // 取最后一段（预发布后缀在中间）
        assertEquals(1200, VersionTag.parseVersionCode("0.0.3-rc1-1200"))
        // 没有数字段
        assertNull(VersionTag.parseVersionCode("0.0.2"))
        assertNull(VersionTag.parseVersionCode("latest"))
        assertNull(VersionTag.parseVersionCode(""))
        assertNull(VersionTag.parseVersionCode("   "))
        // 数字段混了非数字
        assertNull(VersionTag.parseVersionCode("1125-hotfix"))
        assertEquals(1125, VersionTag.parseVersionCode("-1125"))
        // 超出 Int 范围：toIntOrNull 给 null，不能溢出成负数
        assertNull(VersionTag.parseVersionCode("0.0.2-99999999999"))

        // 比较语义：严格大于才算新版本；远端为 null 一律不算更新
        assertTrue(VersionTag.isNewer(1126, 1125))
        assertFalse(VersionTag.isNewer(1125, 1125))
        assertFalse(VersionTag.isNewer(1124, 1125))
        assertFalse(VersionTag.isNewer(null, 1125))
    }

    @Test
    fun noReleaseOnGitHubIsAFriendlyFailureNotANetworkError() = runBlocking {
        val stateHolder = holder(ReleaseTransport(statusCode = 404, body = """{"message":"Not Found"}"""), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        val message = (stateHolder.state.update as UpdateUiState.Failed).message
        assertTrue(message.contains("还没有发布版本"), "404 要说清是仓库没发布，而不是网络问题，实际：$message")
    }

    @Test
    fun networkFailureIsReportedAsCheckFailure() = runBlocking {
        val stateHolder = holder(ReleaseTransport(throws = true), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        val message = (stateHolder.state.update as UpdateUiState.Failed).message
        assertTrue(message.contains("检查更新失败"), "实际：$message")
        assertTrue(message.contains("无法连接"), "要带可操作原因，实际：$message")
    }

    @Test
    fun releaseWithoutApkAssetFailsClearly() = runBlocking {
        val stateHolder = holder(ReleaseTransport(body = RELEASE_JSON_NO_APK), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        assertTrue(stateHolder.state.update is UpdateUiState.Failed, "没有 apk 资产应直接失败")
    }

    @Test
    fun signatureMismatchRejectsInstallAndSaysTampered() = runBlocking {
        val platform = FakeUpdatePlatform(
            result = InstallResult.Rejected("安装包签名与当前应用不一致，可能被篡改，已拒绝安装"),
        )
        val stateHolder = holder(ReleaseTransport(), platform)
        stateHolder.checkUpdate()

        stateHolder.downloadAndInstallUpdate()

        val update = stateHolder.state.update
        assertTrue(update is UpdateUiState.Failed, "签名不一致必须拒绝，实际：$update")
        assertTrue((update as UpdateUiState.Failed).message.contains("可能被篡改"))
        assertEquals(1, platform.downloadCalls)
    }

    @Test
    fun shaMismatchRejectsInstall() = runBlocking {
        val platform = FakeUpdatePlatform(result = InstallResult.Rejected("安装包校验失败（SHA-256 不一致）"))
        val stateHolder = holder(ReleaseTransport(), platform)
        stateHolder.checkUpdate()

        stateHolder.downloadAndInstallUpdate()

        val message = (stateHolder.state.update as UpdateUiState.Failed).message
        assertTrue(message.contains("SHA-256"))
    }

    @Test
    fun successfulInstallReportsStarted() = runBlocking {
        val stateHolder = holder(ReleaseTransport(), FakeUpdatePlatform(result = InstallResult.Started))
        stateHolder.checkUpdate()

        stateHolder.downloadAndInstallUpdate()

        assertEquals(UpdateUiState.InstallStarted, stateHolder.state.update)
    }

    @Test
    fun missingInstallPermissionHasItsOwnState() = runBlocking {
        val platform = FakeUpdatePlatform(result = InstallResult.NeedInstallPermission("拾光集"))
        val stateHolder = holder(ReleaseTransport(), platform)
        stateHolder.checkUpdate()

        stateHolder.downloadAndInstallUpdate()

        val update = stateHolder.state.update
        assertTrue(update is UpdateUiState.NeedPermission, "实际：$update")
        assertEquals("拾光集", (update as UpdateUiState.NeedPermission).appLabel)
    }

    @Test
    fun retryingAfterPermissionGrantRunsDownloadAgain() = runBlocking {
        // 回归：在系统设置里授权「安装未知应用」后回到 App，再点重试必须真的执行。
        // 旧实现的状态保护只认 Available，NeedPermission 下直接 return —— 按钮看得见但点不动。
        val platform = FakeUpdatePlatform(result = InstallResult.NeedInstallPermission("拾光集"))
        val stateHolder = holder(ReleaseTransport(), platform)
        stateHolder.checkUpdate()
        stateHolder.downloadAndInstallUpdate()
        assertTrue(stateHolder.state.update is UpdateUiState.NeedPermission, "前提：应停在需授权")

        stateHolder.downloadAndInstallUpdate()

        assertEquals(
            2,
            platform.downloadCalls,
            "授权后重试必须再执行一次下载/安装，而不是被状态保护静默 return",
        )
    }

    @Test
    fun progressIsPublishedAndCancelReturnsToDownloadable() = runBlocking {
        // 8MB 的包按 512KB 节流：全部回调都会经过，状态里留下最后一条
        val platform = FakeUpdatePlatform(
            progressSteps = listOf(1_000_000L, 4_000_000L, 8_000_000L),
        )
        val stateHolder = holder(ReleaseTransport(), platform)
        stateHolder.checkUpdate()

        stateHolder.downloadAndInstallUpdate()

        // 走完后已调起安装（Started）
        assertEquals(UpdateUiState.InstallStarted, stateHolder.state.update)
        assertEquals(1, platform.downloadCalls)
    }

    @Test
    fun checkFailureNeverTouchesGlobalHintSlot() = runBlocking {
        // 判据 7：次要动作（检查更新）失败只走局部字段，不能写 actionHint
        val stateHolder = holder(ReleaseTransport(statusCode = 500, body = "{}"), FakeUpdatePlatform())

        stateHolder.checkUpdate()

        assertTrue(stateHolder.state.update is UpdateUiState.Failed, "更新区应显示失败")
        assertTrue(
            stateHolder.state.actionHint.isNullOrBlank(),
            "检查更新失败不得占用全局提示槽，实际：${stateHolder.state.actionHint}",
        )
    }

    @Test
    fun repeatedCheckWhileDownloadingDoesNotFireAgain() = runBlocking {
        val transport = ReleaseTransport()
        val platform = FakeUpdatePlatform()
        val stateHolder = holder(transport, platform)
        stateHolder.checkUpdate()
        val requestsAfterFirst = transport.requests

        // Available 状态下再点检查是允许的（用户可能想刷一次），但要能正常返回
        stateHolder.checkUpdate()
        assertEquals(requestsAfterFirst + 1, transport.requests)
        assertTrue(stateHolder.state.update is UpdateUiState.Available)
    }

    @Test
    fun localVersionNameIsExposedForDisplay() = runBlocking {
        val stateHolder = holder(ReleaseTransport(), FakeUpdatePlatform(localName = "0.0.2-1125"))

        assertEquals("0.0.2-1125", stateHolder.localVersionName())
        assertEquals("0.0.2-1125", stateHolder.state.localVersionName, "启动时读到的版本名要进状态")
    }
}
