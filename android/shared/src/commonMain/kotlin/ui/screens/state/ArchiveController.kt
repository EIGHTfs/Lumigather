// 压缩包子状态机（第 7 个）：扫描"当前目录"里的压缩包、拼包内图片 URL、按包合成序列帧 GIF。
//
// 定位很关键：压缩包**不是独立视图**，是当前目录内容的一部分 —— 图片网格之后追加的一组
// 可折叠区块（每个包 = 标题行 + 包内图片格）。所以这里的每条状态都挂在"当前目录"上，
// 换目录/换服务器即整体作废。
//
// 边界：客户端不解压、不编码 GIF、不解析 ini。解压是服务端 7zz，翻转与帧间隔换算在服务端，
// 这里只拼 URL + 管状态 + 报错。
//
// GIF 为什么是"按包"的状态而不是全局一个：一个目录下可能同时有多个序列帧包，
// 各自独立生成、独立失败；用一个全局值会让后生成的那个覆盖前一个。

package ui.screens.state

import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.ArchiveEntry
import com.eightfs.lumigather.data.ImageItem
import com.eightfs.lumigather.data.LumigatherRepository

class ArchiveController(
    private val channel: StateChannel,
    private val repositoryProvider: () -> LumigatherRepository?,
) {

    /**
     * 扫描某个目录下的压缩包。
     *
     * 每次进目录都重新扫：包可能被别的设备/用户增删，缓存一份会让人看到"已经删掉的包"。
     * 目录为空是常态（不是错误），静默清空、不弹提示。
     */
    suspend fun loadArchives(dir: String) {
        if (dir.isBlank()) {
            channel.mutate {
                it.copy(
                    archiveDir = "",
                    archives = emptyList(),
                    archivesLoading = false,
                    archiveScanError = null,
                    zipGifStates = emptyMap(),
                )
            }
            return
        }
        val activeRepository = repositoryProvider()
        if (activeRepository == null) {
            channel.mutate { it.copy(archivesLoading = false, archives = emptyList()) }
            return
        }

        channel.mutate {
            it.copy(
                archiveDir = dir,
                archivesLoading = true,
                // 换目录：上一个目录里各包的 GIF 状态全部作废（URL 里含旧路径）
                zipGifStates = emptyMap(),
            )
        }

        when (val result = activeRepository.scanArchives(dir)) {
            is ApiResult.Ok -> channel.mutate {
                it.copy(
                    // 服务端的 /zip/scan 是**递归**扫描：返回的包里混着当前层、上一层、更深子目录里的。
                    // 全都当本层显示会让一个只有子目录的目录被上百个「压缩包」卡片淹没，
                    // 而且点开还会触发合成动图（那些包多半不是序列帧），看起来就像客户端乱处理。
                    // 这里只保留「直接父目录就是当前目录」的包，子目录里的留给进入子目录时再扫。
                    archives = result.value.filter { entry -> parentOf(entry.path) == trimTrailingSlash(dir) },
                    archivesLoading = false,
                    archiveScanError = null,
                )
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(
                    archives = emptyList(),
                    archivesLoading = false,
                    // 服务端 400 的中文 error 优先（如「目录无效」），拿不到才用数据层兜底文案。
                    // 写独立字段而不是 actionHint：扫包失败是局部问题，不该盖掉更重要的全局提示
                    archiveScanError = describeScanFailure(serverMessageOf(result.error)),
                )
            }
        }
    }

    /**
     * 展开某个压缩包：网格内容切成包内图片。
     *
     * 包内图片在这里一次算好存进状态（而不是让 UI 每次重组去拼 URL）：
     * 拼接含 RFC 3986 编码与 `..` 过滤，属数据决策，不该散到渲染层。
     */
    fun openArchive(path: String) {
        val entry = channel.state.archives.firstOrNull { archive -> archive.path == path } ?: return
        channel.mutate {
            it.copy(
                openedArchive = entry,
                archiveImages = zipImagesOf(entry),
                // 换内容就清放大：包内图片与目录图片是两套绝对地址，留着旧 key 无意义
                expandedEntryKey = null,
                // 展开包 = 进入下一层，返回上级要能回到目录层
                canGoUp = true,
            )
        }
    }

    /** 收起当前包，回到目录层。 */
    fun closeArchive() {
        channel.mutate {
            it.copy(openedArchive = null, archiveImages = emptyList(), expandedEntryKey = null)
        }
    }

    /** 折叠/展开某个包。收起状态留在状态里，切分类回来仍然保留。 */
    fun toggleCollapsed(zipPath: String) {
        channel.mutate {
            val collapsed = if (zipPath in it.collapsedArchives) {
                it.collapsedArchives - zipPath
            } else {
                it.collapsedArchives + zipPath
            }
            it.copy(collapsedArchives = collapsed)
        }
    }

    /**
     * 请求合成某个包的 GIF。
     *
     * 防重复：该包已在生成中就返回。服务端合成要几秒，界面会在包展开时自动触发一次，
     * 重组/Lazy 复用时可能再调一次，靠这个 guard 保证只发一个任务。
     */
    fun requestGif(zipPath: String) {
        val current = channel.state
        if (current.zipGifStates[zipPath] is GifState.Generating) return
        val activeRepository = repositoryProvider() ?: return

        // Generating 里带着 URL：界面拿它去加载，加载结果再回传到这里
        val url = activeRepository.zipGifUrl(zipPath)
        channel.mutate {
            it.copy(zipGifStates = it.zipGifStates + (zipPath to GifState.Generating(url)))
        }
    }

    /** 图片加载成功：生成中 → 就绪（这个回调是"合成完成"的唯一可信信号）。 */
    fun onGifLoaded(zipPath: String, url: String) {
        val current = channel.state.zipGifStates[zipPath]
        if (current is GifState.Generating && current.url == url) {
            channel.mutate {
                it.copy(zipGifStates = it.zipGifStates + (zipPath to GifState.Ready(url)))
            }
        }
    }

    /**
     * 图片加载失败：去问服务端原因。
     *
     * 失败可能是网络断，也可能是服务端 400（压缩包无效 / 没有序列帧）。
     * 只有后一种能拿到中文说明，所以这一步本身失败也不该崩，退化成通用文案。
     */
    suspend fun onGifLoadFailed(zipPath: String, url: String) {
        val current = channel.state.zipGifStates[zipPath]
        if (current !is GifState.Generating || current.url != url) return
        val detail = repositoryProvider()?.gifFailureText(url).orEmpty()
        val message = if (detail.isBlank()) GIF_FAILED_GENERIC else "$GIF_FAILED_PREFIX$detail"
        channel.mutate {
            it.copy(zipGifStates = it.zipGifStates + (zipPath to GifState.Failed(message)))
        }
    }

    /** 丢弃某个包的 GIF（失败重试时回到 Idle，下次展开会重新触发）。 */
    fun resetGif(zipPath: String) {
        channel.mutate { it.copy(zipGifStates = it.zipGifStates - zipPath) }
    }

    /**
     * 把包内条目名拼成可直接渲染的图片模型。
     *
     * 条目名在 [LumigatherRepository.zipImageUrl] 里做 RFC 3986 编码；含 `..` 的条目会得到
     * 空 URL，这里直接过滤 —— 服务端本来也会拒，不留一堆必然失败的格子。
     */
    fun zipImagesOf(entry: ArchiveEntry): List<ImageItem> {
        val activeRepository = repositoryProvider() ?: return emptyList()
        return entry.images.mapNotNull { entryName ->
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
    }

    /**
     * 换服务器时的清理（由 facade 调用）。
     *
     * 压缩包路径、包内图片、已合成的 GIF 全是旧服务器上的东西，留着每条都点不开。
     * 折叠状态也一并清空：路径是旧服务器的，留着只会是永不命中的垃圾键。
     */
    fun resetArchive() {
        channel.mutate {
            it.copy(
                archiveDir = "",
                archives = emptyList(),
                archivesLoading = false,
                archiveScanError = null,
                collapsedArchives = emptySet(),
                zipGifStates = emptyMap(),
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
    private fun serverMessageOf(error: ApiError): String =
        if (error is ApiError.Http && error.serverMessage.isNotBlank()) {
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

private const val SCAN_FAILED_PREFIX = "扫描压缩包失败："
private const val SCAN_FAILED_GENERIC = "扫描压缩包失败，请稍后重试"
private const val GIF_FAILED_PREFIX = "合成动图失败："
private const val GIF_FAILED_GENERIC = "合成动图失败，请稍后重试"

// 路径工具（纯函数，便于单测）：判断压缩包是否**直接**位于某个目录下。
// 服务端 /zip/scan 是递归的，不带这层过滤就会把子目录里的包也列到当前层。
private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "" else trimmed.substring(0, cut)
}

private fun trimTrailingSlash(path: String): String =
    if (path.length > 1) path.trimEnd('/') else path
