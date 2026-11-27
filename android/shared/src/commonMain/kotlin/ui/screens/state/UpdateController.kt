// 应用更新子状态机（第 8 个）：检查新版本 → 下载 → 校验 → 调起安装。
//
// 为什么单独成状态机而不是塞进设置页的本地状态：
//   1. 下载要跨页面存活（用户翻到主界面再回来不该丢进度）；
//   2. 状态需要被单测覆盖（版本比较、哈希不匹配、签名不匹配、404）；
//   3. 失败必须走**局部字段**而不是全局那一行提示槽 —— 检查更新是次要动作，
//      它失败绝不能盖掉「登录已过期」这类与用户当前操作直接相关的提示。
//
// 依赖只有两个：GitHub 客户端（查发布）+ 平台能力（下载/校验/安装），都不依赖登录态与服务端地址。

package ui.screens.state

import com.eightfs.lumigather.data.AppUpdateClient
import com.eightfs.lumigather.data.AppUpdatePlatform
import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.InstallResult
import com.eightfs.lumigather.data.RemoteRelease
import com.eightfs.lumigather.data.VersionTag
import kotlin.coroutines.cancellation.CancellationException

/** 更新区的一屏状态。失败/需授权都是局部状态，不进全局提示槽。 */
sealed interface UpdateUiState {
    /** 还没检查过：界面显示「当前版本 + 检查更新」。 */
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** 发现新版本，等用户点「下载并安装」。 */
    data class Available(val release: RemoteRelease) : UpdateUiState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateUiState

    /** 已是最新。 */
    data object Latest : UpdateUiState

    /** 已调起系统安装界面（用户要在系统界面点确认，应用内到此为止）。 */
    data object InstallStarted : UpdateUiState

    /** 缺「安装未知应用」授权：引导用户去系统设置后回来再点一次。 */
    data class NeedPermission(val appLabel: String) : UpdateUiState

    /** 失败原因（网络/404/校验不通过…），直接内联显示。 */
    data class Failed(val message: String) : UpdateUiState
}

class UpdateController(
    private val channel: StateChannel,
    private val clientProvider: () -> AppUpdateClient,
    // 平台实现用 provider 延迟创建：它要 Android Context，构造状态下机的时机可能早于
    // MainActivity 安装上下文；只在真正点「检查更新」时才取，拿不到就会明确报错而不是崩溃
    private val platformProvider: () -> AppUpdatePlatform,
) {

    private var cachedPlatform: AppUpdatePlatform? = null

    private fun platform(): AppUpdatePlatform =
        cachedPlatform ?: platformProvider().also { cachedPlatform = it }

    // 用户点了取消：下载循环通过进度回调看到它后抛 CancellationException 中断
    private var cancelRequested = false

    /**
     * 当前安装版本名，给界面显示「当前版本 x.y.z」。
     *
     * 取不到（Context 尚未就绪、单测环境）时返回空串而不是抛异常：显示"版本未知"是可接受的降级，
     * 让整个设置页崩掉不可接受。
     */
    fun localVersionName(): String = runCatching { platform().localVersionName() }.getOrDefault("")

    /**
     * 检查更新。
     *
     * 重复点击直接返回：检查中/下载中再点没有意义，还会在弱网下叠出多个请求。
     */
    suspend fun check() {
        val current = channel.state.update
        if (current is UpdateUiState.Checking || current is UpdateUiState.Downloading) return
        channel.mutate { it.copy(update = UpdateUiState.Checking) }

        val localCode = platform().localVersionCode()
        when (val result = clientProvider().fetchLatestRelease()) {
            is ApiResult.Err -> channel.mutate {
                it.copy(update = UpdateUiState.Failed(describeCheckFailure(result.error)))
            }

            is ApiResult.Ok -> applyRelease(result.value, localCode)
        }
    }

    /** 把远端发布与本机版本比对后落状态。版本号解析不出时给可读提示，不抛异常。 */
    private fun applyRelease(release: RemoteRelease, localVersionCode: Int) {
        val remoteCode = release.versionCode
        val next = when {
            remoteCode == null -> UpdateUiState.Failed(
                "无法从发布版本号「${release.versionName}」解析出版本序号，请到发布页手动确认",
            )

            VersionTag.isNewer(remoteCode, localVersionCode) -> UpdateUiState.Available(release)
            else -> UpdateUiState.Latest
        }
        channel.mutate { it.copy(update = next) }
    }

    /**
     * 下载并安装（从 [UpdateUiState.Available] 进入）。
     *
     * 四个退出分支各自对应一种用户可感知的结局：已调起安装 / 需授权 / 校验拒绝 / 普通失败。
     */
    suspend fun downloadAndInstall() {
        val current = channel.state.update
        if (current !is UpdateUiState.Available) return
        val release = current.release

        cancelRequested = false
        channel.mutate {
            it.copy(update = UpdateUiState.Downloading(0L, release.sizeBytes))
        }

        var lastPublishedBytes = 0L
        try {
            val result = platform().downloadVerifyAndInstall(release) { downloadedBytes, totalBytes ->
                // 取消检查放在进度回调里：平台侧是阻塞下载循环，这是唯一能及时中断的时机
                if (cancelRequested) throw CancellationException(CANCELLED_MESSAGE)
                if (downloadedBytes - lastPublishedBytes >= PROGRESS_PUBLISH_STEP_BYTES || downloadedBytes == totalBytes) {
                    lastPublishedBytes = downloadedBytes
                    channel.mutate {
                        it.copy(update = UpdateUiState.Downloading(downloadedBytes, totalBytes))
                    }
                }
            }
            channel.mutate { it.copy(update = toUiState(result, release)) }
        } catch (cancelled: CancellationException) {
            // 用户主动取消：回到"可再次下载"的状态，不报错
            if (cancelRequested) {
                channel.mutate { it.copy(update = UpdateUiState.Available(release)) }
            } else {
                throw cancelled
            }
        } finally {
            cancelRequested = false
        }
    }

    /** 用户取消下载：置标志 + 立刻把界面切回可重试态（下载线程稍后退出）。 */
    fun cancel() {
        if (channel.state.update !is UpdateUiState.Downloading) return
        cancelRequested = true
    }

    // 结果 → UI 状态。校验拒绝与普通失败分开：前者是安全事件，界面上要让用户意识到严重性
    private fun toUiState(result: InstallResult, release: RemoteRelease): UpdateUiState = when (result) {
        InstallResult.Started -> UpdateUiState.InstallStarted
        is InstallResult.NeedInstallPermission -> UpdateUiState.NeedPermission(result.appLabel)
        is InstallResult.Rejected -> UpdateUiState.Failed(result.message)
        is InstallResult.Failed -> UpdateUiState.Failed(result.message)
    }

    private fun describeCheckFailure(error: ApiError): String = when (error) {
        // 404 是"仓库还没发过 Release"，不是网络问题，要说清以免用户反复重试
        is ApiError.Http -> error.serverMessage.ifBlank { "检查更新失败（HTTP ${error.statusCode}）" }
        else -> "检查更新失败：${error.userMessage}"
    }

    // 释放：换服务器/重置时把更新区恢复默认（下载中的任务由调用方取消）
    fun reset() {
        cancelRequested = false
        channel.mutate { it.copy(update = UpdateUiState.Idle) }
    }
}

// 进度发布节流：8MB 的包按 64KB 回调会有 128 次重组，按 512KB 一次足够顺滑
private const val PROGRESS_PUBLISH_STEP_BYTES = 512L * 1024L

private const val CANCELLED_MESSAGE = "已取消下载"
