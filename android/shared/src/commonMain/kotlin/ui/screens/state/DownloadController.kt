// 下载子状态机：把"当前详情图存进相册"这件事从头管到尾（进度、结果、去重、取消）。
//
// 它只依赖两样东西：状态通道（读选中图片、写下载状态）与相册下载能力。
// 不需要知道仓库、地址、登录态 —— 所以它的构造参数只有两个，单测可以只给它这两样。

package ui.screens.state

import kotlin.coroutines.cancellation.CancellationException
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader

class DownloadController(
    private val channel: StateChannel,
    private val imageDownloader: ImageDownloader,
) {

    /**
     * 下载当前详情图到系统相册。
     *
     * 防重复：一开始就把状态切成 Downloading，第二次调用在入口处直接返回。
     * 因为状态是同步写入的（在第一个挂起点之前），连点两下也只有一次会真正跑下去。
     *
     * 失败不弹窗、不崩：三类失败原因各有中文提示，直接显示在动作条上方。
     */
    suspend fun downloadSelectedImage() {
        val current = channel.state
        val image = current.selectedImage ?: return
        if (current.downloadState is DownloadUiState.Downloading) return

        channel.mutate { it.copy(downloadState = DownloadUiState.Downloading, actionHint = null) }

        // 结果一次性算成一个新状态再写回：中间不暴露"Downloading 已被清掉但结果还没写"的空档，
        // 否则按钮会在半路恢复可点。
        //
        // 外层兜一层 catch 是防御性的：接口约定"实现不抛异常"，但平台实现可能有漏网的异常类型，
        // 一旦抛出来没有兜底，状态就会永久停在"保存中…"、按钮永久禁用 —— 那是最难受的一种坏。
        val nextState = try {
            when (val result = imageDownloader.saveToGallery(image.absoluteUrl, image.name)) {
                is DownloadResult.Success -> DownloadUiState.Done("已保存到相册")
                is DownloadResult.AlreadySaved -> DownloadUiState.Done("相册里已有这张图，未重复保存")
                is DownloadResult.Failure -> DownloadUiState.Failed(result.message)
            }
        } catch (cancelled: CancellationException) {
            // 协程被取消（页面销毁等）：先把状态复位，再把取消原样抛出 ——
            // 吞掉取消会让上层的结构化并发逻辑失效。
            channel.mutate { it.copy(downloadState = DownloadUiState.Idle) }
            throw cancelled
        } catch (failure: Throwable) {
            DownloadUiState.Failed("保存失败：${failure.message ?: "未知错误"}")
        }
        channel.mutate { it.copy(downloadState = nextState) }
    }

    /**
     * 换了一张图：把上一张的下载结果连同提示一起清掉。
     *
     * 不清的话，用户点开新图会看到上一张留下的"已保存到相册"，以为新图也存过了。
     * 由侧栏子状态机通过回调通知到这里（它不知道下载状态怎么表示，也不该知道）。
     */
    fun resetForImageChange() {
        channel.mutate { it.copy(downloadState = DownloadUiState.Idle, actionHint = null) }
    }
}
