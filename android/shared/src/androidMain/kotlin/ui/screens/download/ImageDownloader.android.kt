// 下载保存到相册的 Android 实现（MediaStore）。
//
// 两条代码路径是硬性事实差异，不是优化选择：
// - API 29（Q）起是 Scoped Storage：写自己的相册条目不需要任何权限，
//   只要插进 MediaStore 并置 IS_PENDING=1，写完再置 0；
// - API 24–28 是旧的"共享存储"模型：必须先有 WRITE_EXTERNAL_STORAGE，
//   而且 MediaStore 只认 DATA 列（真实文件路径），所以要自己建目录、写文件、再登记。
// 两条路都必须覆盖：本工程 minSdk 24，而用户机器上两种版本都可能存在。
//
// 所有异常都在这里收敛成 DownloadResult，页面永远拿到"成功 / 已存在 / 带原因的中文失败"。

package ui.screens.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.screens.settings.AndroidHostContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 相册里的子目录名：集中放本项目存的图，方便用户一眼看出是这里存的。
private const val GALLERY_FOLDER = "Lumigather"

// 下载超时：局域网内连通很快；大图（几 MB）读取给足 60 秒。
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

// 插入时先标记"写入中"，写完再清掉 —— 这样相册扫描器不会看到半截文件。
private const val PENDING_WRITING = 1
private const val PENDING_DONE = 0

// 拿不到扩展名时按 jpeg 登记：相册靠 MIME 决定能否预览，登记成 octet-stream 会显示成坏文件。
private const val FALLBACK_MIME_TYPE = "image/jpeg"

private const val EXTENSION_FALLBACK = "jpg"
private const val EXTENSION_SEPARATOR = '.'

actual fun createImageDownloader(): ImageDownloader = MediaStoreImageDownloader()

private class MediaStoreImageDownloader : ImageDownloader {

    override suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) {
            return@withContext DownloadResult.Failure(DownloadFailureKind.Unknown, "这张图没有可用的下载地址")
        }
        val context = AndroidHostContext.current()
            ?: return@withContext DownloadResult.Failure(
                DownloadFailureKind.Unknown,
                "应用上下文未就绪，请重启应用后重试",
            )

        // 只有 API 28 及以下需要权限；29+ 的 Scoped Storage 不需要任何权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ensureLegacyStoragePermission()) {
            return@withContext DownloadResult.Failure(
                DownloadFailureKind.PermissionDenied,
                "保存到相册需要「存储」权限。请在系统设置 → 应用 → 拾光集 → 权限里允许后再试",
            )
        }

        val displayName = normalizeFileName(fileName)

        // 先查重再下载：已经在相册里就没必要把整张图拉一遍
        if (alreadyInGallery(context, displayName)) {
            return@withContext DownloadResult.AlreadySaved
        }

        val bytes = downloadBytes(imageUrl)
            ?: return@withContext DownloadResult.Failure(
                DownloadFailureKind.Network,
                "下载失败，请检查网络或服务器是否可访问",
            )
        if (bytes.isEmpty()) {
            return@withContext DownloadResult.Failure(DownloadFailureKind.Network, "服务器返回了空文件，无法保存")
        }

        try {
            val uri = insertIntoGallery(context, displayName, bytes)
                ?: return@withContext DownloadResult.Failure(
                    DownloadFailureKind.Storage,
                    "写入相册失败，可能是存储空间不足",
                )
            if (uri == Uri.EMPTY) {
                return@withContext DownloadResult.Failure(DownloadFailureKind.Storage, "写入相册失败，可能是存储空间不足")
            }
            DownloadResult.Success
        } catch (denied: SecurityException) {
            // 权限在"检查通过"之后又被收回（用户去设置里关掉、或厂商 ROM 的行为）：
            // 仍然给可操作提示，不让它变成"未知错误"
            DownloadResult.Failure(
                DownloadFailureKind.PermissionDenied,
                "没有写入相册的权限。请在系统设置 → 应用 → 拾光集 → 权限里允许后再试",
            )
        } catch (failure: Exception) {
            DownloadResult.Failure(DownloadFailureKind.Unknown, "保存失败：${failure.message ?: "未知错误"}")
        }
    }

    // 权限申请要占着主线程弹框，所以放在 IO 里 await 也不会卡住 UI（续体在结果回来时才恢复）
    private suspend fun ensureLegacyStoragePermission(): Boolean =
        AndroidPermissionBridge.ensureWriteStoragePermission()

    /**
     * 查重：判断相册里是否已有同名文件。
     *
     * 29+ 用 DISPLAY_NAME + RELATIVE_PATH 精确匹配本目录；28- 直接看文件是否还存在。
     * 这一步让"同一个按钮连点两次"不会在相册里堆出两份 —— 用户要的是"存一张"，不是"存几张"。
     */
    private fun alreadyInGallery(context: Context, displayName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?"
            val args = arrayOf(displayName, RELATIVE_PATH_ROOT)
            context.contentResolver.query(collection, PROJECTION_ID, selection, args, null)
                ?.use { cursor -> cursor.count > 0 } ?: false
        } else {
            legacyTargetFile(displayName).exists()
        }
    }

    private fun insertIntoGallery(context: Context, displayName: String, bytes: ByteArray): Uri? {
        val mimeType = mimeTypeOf(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertScoped(context, displayName, mimeType, bytes)
        } else {
            insertLegacy(context, displayName, mimeType, bytes)
        }
    }

    // Q+：插一条 pending 记录 → 写入流 → 清 pending。写入失败要删掉占位记录，否则相册里留一条空条目。
    private fun insertScoped(context: Context, displayName: String, mimeType: String, bytes: ByteArray): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH_ROOT)
            put(MediaStore.Images.Media.IS_PENDING, PENDING_WRITING)
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            val output = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            output.use { stream -> stream.write(bytes) }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, PENDING_DONE)
            resolver.update(uri, values, null, null)
            return uri
        } catch (failure: Exception) {
            // 半截文件必须清掉：留着会让用户在相册里看到一张打不开的图
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    // 28-：先落成真实文件，再把 DATA 列登记进 MediaStore（旧模型只认路径）
    @Suppress("DEPRECATION")
    private fun insertLegacy(context: Context, displayName: String, mimeType: String, bytes: ByteArray): Uri? {
        val target = legacyTargetFile(displayName)
        val directory = target.parentFile ?: return null
        if (!directory.exists() && !directory.mkdirs()) return null
        target.writeBytes(bytes)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATA, target.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    @Suppress("DEPRECATION")
    private fun legacyTargetFile(displayName: String): File {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File(File(pictures, GALLERY_FOLDER), displayName)
    }

    private fun downloadBytes(imageUrl: String): ByteArray? {
        val connection = runCatching { URL(imageUrl).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) return null
            connection.inputStream.use { stream -> stream.readBytes() }
        } catch (failure: Exception) {
            // 断网、超时、连接被重置都归到网络失败一类：对用户来说补救动作是同一个
            null
        } finally {
            connection.disconnect()
        }
    }

    // 文件名兜底：服务端给的 name 可能没有扩展名，而相册要按扩展名推 MIME
    private fun normalizeFileName(fileName: String): String {
        val trimmed = fileName.trim().ifEmpty { DEFAULT_FILE_NAME }
        return if (trimmed.lastIndexOf(EXTENSION_SEPARATOR) > 0) {
            trimmed
        } else {
            "$trimmed.$EXTENSION_FALLBACK"
        }
    }

    private fun mimeTypeOf(displayName: String): String {
        val extension = displayName.substringAfterLast(EXTENSION_SEPARATOR, "").lowercase()
        if (extension.isEmpty()) return FALLBACK_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK_MIME_TYPE
    }

    private companion object {
        // Q+ 的相对路径必须带尾部斜杠，否则会落到根目录而不是子目录。
        // 不能写成 const：Environment.DIRECTORY_PICTURES 是 Java 静态字段，不是 Kotlin 常量。
        private val RELATIVE_PATH_ROOT = "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER/"
        private const val DEFAULT_FILE_NAME = "lumigather-image"
        private val PROJECTION_ID = arrayOf(MediaStore.Images.Media._ID)
    }
}
