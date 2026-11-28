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
                    archiveImages = emptyList(),
                    archivesLoading = false,
                    archiveScanError = null,
                )
            }
            return
        }
        val activeRepository = repositoryProvider()
        if (activeRepository == null) {
            channel.mutate {
                it.copy(archivesLoading = false, archives = emptyList(), archiveImages = emptyList())
            }
            return
        }

        channel.mutate {
            it.copy(
                archiveDir = dir,
                archivesLoading = true,
                // 换目录：上一个目录的包内图全部作废（URL 里含旧包的绝对路径）
                archiveImages = emptyList(),
            )
        }

        when (val result = activeRepository.scanArchives(dir)) {
            is ApiResult.Ok -> {
                // 只保留含图的包。服务端 /zip/scan 扫的是**当前层**（实测 108 个包的父目录
                // 全部等于 dir），所以不需要按父目录过滤；真正要滤掉的是空包 ——
                // 实测同一个目录里 70/108 个包 images 为空，网页版也只渲染有图的
                // （server/public/app.js 的 zipList = zips.filter(z => z.images && z.images.length)）。
                // 不滤的话网格里会出现一批点开什么都没有的「0 张」卡片。
                val withImages = result.value.filter { entry -> entry.images.isNotEmpty() }
                // 包内图直接摊成普通图片条目：没有"打开压缩包"这层概念，也不判断序列帧
                val images = withImages.flatMap { entry -> zipImagesOf(entry) }
                channel.mutate {
                    it.copy(
                        archives = withImages,
                        archiveImages = images,
                        archivesLoading = false,
                        archiveScanError = null,
                    )
                }
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(
                    archives = emptyList(),
                    archiveImages = emptyList(),
                    archivesLoading = false,
                    // 服务端 400 的中文 error 优先（如「目录无效」），拿不到才用数据层兜底文案。
                    // 写独立字段而不是 actionHint：扫包失败是局部问题，不该盖掉更重要的全局提示
                    archiveScanError = describeScanFailure(serverMessageOf(result.error)),
                )
            }
        }
    }

    /**
     * 把包内条目名拼成可直接渲染的图片模型。
     *
     * 条目名在 [LumigatherRepository.zipImageUrl] 里做 RFC 3986 编码；是否安全（含 `..`）
     * 由服务端判定并回 400 —— 客户端不再重复实现一遍路径穿越防护。
     */
    private fun zipImagesOf(entry: ArchiveEntry): List<ImageItem> {
        val activeRepository = repositoryProvider() ?: return emptyList()
        return entry.images.map { entryName ->
            ImageItem(
                name = entryName.substringAfterLast('/'),
                path = "${entry.path}/$entryName",
                relativeUrl = "",
                sizeText = "",
                modifiedIso = "",
                absoluteUrl = activeRepository.zipImageUrl(entry.path, entryName),
            )
        }
    }

    /**
     * 换服务器时的清理（由 facade 调用）。
     *
     * 压缩包路径与包内图片全是旧服务器上的东西，留着每条都点不开。
     */
    fun resetArchive() {
        channel.mutate {
            it.copy(
                archiveDir = "",
                archives = emptyList(),
                archiveImages = emptyList(),
                archivesLoading = false,
                archiveScanError = null,
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

private const val SCAN_FAILED_PREFIX = "扫描压缩包失败："
private const val SCAN_FAILED_GENERIC = "扫描压缩包失败，请稍后重试"
