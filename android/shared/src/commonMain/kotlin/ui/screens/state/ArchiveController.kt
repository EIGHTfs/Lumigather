// 压缩包子状态机（第 7 个）：扫描压缩包、打开包内图片、合成序列帧 GIF。
//
// 边界必须守住：**客户端不解压、不编码 GIF、不解析 ini**。
// 解压是服务端的 7zz，翻转与帧间隔换算在 server/lib/ 里；这里只拼 URL + 管状态 + 报错。
//
// 依赖与其它子状态机一致：状态通道 + repository provider，没有兄弟状态机的引用。
//
// GIif 的"生成中/成功/失败"为什么由 UI 回调最终确认：GIF 是交给图片加载器按 URL 拉的
// （几 MB 的字节不该在客户端搬一遍），所以"加载完成/失败"只有图片层知道。
// 失败时再用二进制通道问一次原因，把服务端那句中文（如「目录无效」）取回来。

package ui.screens.state

import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ArchiveHeuristics
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository

class ArchiveController(
    private val channel: StateChannel,
    private val repositoryProvider: () -> LumigatherRepository?,
) {

    /**
     * 扫描某个目录下的压缩包。
     *
     * 每次进入压缩包视图都重新扫：包可能被用户或别的设备增删，缓存一份会让人看到"已经删掉的包"。
     */
    suspend fun loadArchives(dir: String) {
        // 先判"有没有目录"：没有目录时提示"先选分类"比"没有服务器"更贴近用户当下该做的事
        if (dir.isBlank()) {
            channel.mutate {
                it.copy(
                    archiveDir = "",
                    archives = emptyList(),
                    archivesLoading = false,
                    actionHint = "请先选择一个分类，再查看它的压缩包",
                )
            }
            return
        }
        val activeRepository = repositoryProvider()
        if (activeRepository == null) {
            channel.mutate { it.copy(archivesLoading = false, actionHint = NO_SERVER) }
            return
        }

        // 进目录视图先复位"包内状态"：否则从上一个包切回来会继续显示旧包的图片
        channel.mutate {
            it.copy(
                archiveDir = dir,
                archivesLoading = true,
                openedArchive = null,
                archiveImages = emptyList(),
                gifState = GifState.Idle,
            )
        }

        when (val result = activeRepository.scanArchives(dir)) {
            is ApiResult.Ok -> channel.mutate {
                it.copy(archives = result.value, archivesLoading = false)
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(
                    archives = emptyList(),
                    archivesLoading = false,
                    // 服务端 400 的中文 error 优先（如「目录无效」），拿不到才用数据层的兜底文案
                    actionHint = describeScanFailure(serverMessageOf(result.error)),
                )
            }
        }
    }

    /**
     * 打开一个压缩包：把包内条目名拼成可加载的绝对 URL。
     *
     * 条目名在 [LumigatherRepository.zipImageUrl] 里做 RFC 3986 编码；含 `..` 的条目
     * 会得到空 URL，这里直接过滤掉 —— 服务端本来也会拒，不留一堆必然失败的格子。
     */
    fun openArchive(entry: ArchiveEntry) {
        val activeRepository = repositoryProvider() ?: return
        val images = entry.images.mapNotNull { entryName ->
            val url = activeRepository.zipImageUrl(entry.path, entryName)
            if (url.isEmpty()) {
                null
            } else {
                ImageItem(
                    name = entryName.substringAfterLast('/'),
                    path = "${entry.path}/$entryName",
                    relativeUrl = "",
                    sizeText = "",
                    modifiedIso = "",
                    absoluteUrl = url,
                )
            }
        }
        channel.mutate {
            it.copy(
                openedArchive = entry,
                archiveImages = images,
                // 换包必须清掉上一个包生成的 GIF，否则会把旧动画当成新包的
                gifState = GifState.Idle,
                actionHint = null,
            )
        }
    }

    // 从包内视图退回压缩包列表
    fun closeArchive() {
        channel.mutate {
            it.copy(
                openedArchive = null,
                archiveImages = emptyList(),
                gifState = GifState.Idle,
            )
        }
    }

    /**
     * 请求合成 GIF（目录序列帧或包内序列帧，取决于当前在哪个视图）。
     *
     * 防重复：生成中直接返回。服务端合成要几秒，连点会排出一串任务把服务端压住。
     */
    fun requestGif() {
        val current = channel.state
        if (current.gifState is GifState.Generating) return

        val activeRepository = repositoryProvider() ?: return
        val url = current.openedArchive
            ?.let { archive -> activeRepository.zipGifUrl(archive.path) }
            ?: activeRepository.directoryGifUrl(current.archiveDir)

        // Generating 里带着 URL：界面拿它去加载，加载结果再回传到这里
        channel.mutate { it.copy(gifState = GifState.Generating(url), actionHint = null) }
    }

    /** 图片加载成功：生成中 → 就绪（这个回调是"合成完成"的唯一可信信号）。 */
    fun onGifLoaded(url: String) {
        val current = channel.state.gifState
        if (current is GifState.Generating && current.url == url) {
            channel.mutate { it.copy(gifState = GifState.Ready(url)) }
        }
    }

    /**
     * 图片加载失败：去问服务端原因。
     *
     * 拉取失败可能是网络断、也可能是服务端 400（目录无效 / 压缩包无效 / 没找到序列帧）。
     * 只有后一种能拿到中文说明，所以这一步失败也不该崩，退化成通用文案。
     */
    suspend fun onGifLoadFailed(url: String) {
        val current = channel.state.gifState
        if (current !is GifState.Generating || current.url != url) return
        val detail = repositoryProvider()?.gifFailureText(url).orEmpty()
        val message = if (detail.isBlank()) GIF_FAILED_GENERIC else "$GIF_FAILED_PREFIX$detail"
        channel.mutate { it.copy(gifState = GifState.Failed(message)) }
    }

    fun dismissGif() {
        channel.mutate { it.copy(gifState = GifState.Idle) }
    }

    /**
     * 换服务器时的清理（由 facade 调用）。
     *
     * 压缩包路径、包内图片、已合成的 GIF 全是旧服务器上的东西，留着每条都点不开。
     * 列表本身不必在这里重拉：换地址会把视图复位成分类视图，下次进压缩包视图会重新扫描。
     */
    fun resetArchive() {
        channel.mutate {
            it.copy(
                archiveDir = "",
                archives = emptyList(),
                archivesLoading = false,
                openedArchive = null,
                archiveImages = emptyList(),
                gifState = GifState.Idle,
            )
        }
    }

    // 扫描失败的文案：服务端的 error 已经是一句中文，直接带前缀用
    private fun describeScanFailure(serverMessage: String): String =
        if (serverMessage.isBlank()) SCAN_FAILED_GENERIC else "$SCAN_FAILED_PREFIX$serverMessage"

    /**
     * 取服务端给的 error 原文（如「目录无效」）。
     *
     * 不能用 error.userMessage：那是数据层给状态码配的通用文案（"服务器返回错误（400）"），
     * 而这里要的正是服务端写的那句话 —— 用户需要知道是"目录无效"还是"压缩包无效"。
     */
    private fun serverMessageOf(error: com.eightfs.lumigather.data.ApiError): String =
        if (error is com.eightfs.lumigather.data.ApiError.Http && error.serverMessage.isNotBlank()) {
            error.serverMessage
        } else {
            error.userMessage
        }
}

/** GIF 状态。[Generating] 带 URL，是为了让界面能立刻开始加载并回调结果。 */
sealed class GifState {
    data object Idle : GifState()
    data class Generating(val url: String) : GifState()
    data class Ready(val url: String) : GifState()
    data class Failed(val message: String) : GifState()
}

// 是否"像序列帧"（决定要不要显示合成动图入口）也放在这层用，免得页面各写一遍
internal fun looksLikeSequence(entry: ArchiveEntry?): Boolean =
    entry != null && ArchiveHeuristics.looksLikeSequence(entry.images)

private const val NO_SERVER = "还没有可用的服务器，请先配置地址"
private const val SCAN_FAILED_PREFIX = "扫描压缩包失败："
private const val SCAN_FAILED_GENERIC = "扫描压缩包失败，请稍后重试"
private const val GIF_FAILED_PREFIX = "合成动图失败："
private const val GIF_FAILED_GENERIC = "合成动图失败，请稍后重试"
