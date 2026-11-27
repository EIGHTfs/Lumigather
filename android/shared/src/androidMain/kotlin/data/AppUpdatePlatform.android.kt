// Android 侧的更新能力实现：下载 → 两道校验 → 调起系统安装器。
//
// 安全设计的核心在这一段（局域网/公网传输都可能被中间人替换）：
//   1. SHA-256：与远端元数据里的 digest 比对（GitHub 给了才校验，没给时跳过并在下方注释说明原因）
//   2. **签名比对**：读待装 APK 的签名证书，与当前已安装应用的签名比对，不一致一律拒绝
//      —— 这是真正的防线：元数据 sha256 与 APK 可以同时被替换，但攻击者拿不到签名私钥。
//
// 不做静默安装：那需要系统签名或 root，平台层面不允许，不要试图绕过。

package com.eightfs.lumigather.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import ui.screens.settings.AndroidHostContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 构造平台实现。
 *
 * Context 取自 [AndroidHostContext]（与设置存储同一个入口），避免为了更新再引入一套 Context 传递。
 */
actual fun createAppUpdatePlatform(): AppUpdatePlatform =
    AndroidAppUpdatePlatform(
        context = requireNotNull(AndroidHostContext.current()) {
            "AndroidHostContext 尚未安装（应在 MainActivity.onCreate 里先调用 install）"
        },
        installer = AppInstallerRegistry.installer,
    )

class AndroidAppUpdatePlatform(
    private val context: Context,
    // 调起安装器 / 打开"未知来源"设置这两件事需要 FileProvider（androidx.core）与 manifest 声明，
    // 都在 app 模块里；shared 只通过这个接口请它代劳，避免给 shared 引入新依赖
    private val installer: ApkInstaller?,
) : AppUpdatePlatform {

    private val packageManager: PackageManager get() = context.packageManager

    override fun localVersionCode(): Int = installedPackageInfo().let { info ->
        @Suppress("DEPRECATION")
        info.versionCode
    }

    override fun localVersionName(): String = installedPackageInfo().let { info ->
        @Suppress("DEPRECATION")
        info.versionName.orEmpty()
    }

    override suspend fun downloadVerifyAndInstall(
        release: RemoteRelease,
        onProgress: (Long, Long) -> Unit,
    ): InstallResult = withContext(Dispatchers.IO) {
        val targetFile = cacheFileFor(release)
        try {
            val downloaded = download(release, targetFile, onProgress)
                ?: return@withContext InstallResult.Failed(DOWNLOAD_FAILED_MESSAGE)

            // 第一道：SHA-256。远端没给 digest 时跳过 —— GitHub 的 release asset 不保证有该字段，
            // 假装校验等于给自己一个"已校验"的错觉，所以这里明确跳过并让签名比对承担全部信任。
            if (release.sha256 != null && !release.sha256.equals(downloaded.sha256, ignoreCase = true)) {
                targetFile.delete()
                return@withContext InstallResult.Rejected(SHA_MISMATCH_MESSAGE)
            }

            // 第二道：签名比对（不可省）
            signatureProblem(targetFile)?.let { problem ->
                targetFile.delete()
                return@withContext InstallResult.Rejected(problem)
            }

            launchInstaller(targetFile)
        } catch (cancelled: CancellationException) {
            // 用户取消：不留半截包，异常继续向上抛给状态层
            targetFile.delete()
            throw cancelled
        } catch (failure: Throwable) {
            targetFile.delete()
            InstallResult.Failed(failure.message ?: DOWNLOAD_FAILED_MESSAGE)
        }
    }

    // ---- 下载 ----

    /** 一次下载的结果：文件大小 + 边下边算的 sha256（省一次读盘）。 */
    private class Downloaded(val sha256: String)

    /**
     * 下载：多源轮询。
     *
     * 为什么不是直连 GitHub：国内直连 objects.githubusercontent.com 常年只有几十 KB/s，
     * 8MB 的包要下三五分钟，中途断一次就前功尽弃 —— 这正是「更新超时」的主因。
     * 公共加速器（ghfast / gh-proxy）实测能把同一份包拉到可用速度；
     * 直连放最后作为兜底，加速器全挂时仍然能下（只是慢）。
     */
    private fun download(
        release: RemoteRelease,
        targetFile: File,
        onProgress: (Long, Long) -> Unit,
    ): Downloaded? {
        var lastFailure: Throwable? = null
        for (prefix in DOWNLOAD_SOURCE_PREFIXES) {
            // 前缀拼接方式就是镜像的通用约定：把完整原始 URL 接在镜像域名后面
            val url = prefix + release.downloadUrl
            try {
                val result = attemptDownload(url, release, targetFile, onProgress)
                if (result != null) return result
                // 非 2xx（比如加速器没有缓存这个包）→ 换下一个源继续
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        if (lastFailure != null) throw lastFailure
        return null
    }

    private fun attemptDownload(
        url: String,
        release: RemoteRelease,
        targetFile: File,
        onProgress: (Long, Long) -> Unit,
    ): Downloaded? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = HTTP_GET
        }
        return try {
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) return null
            // 总字节优先用远端声明的 size（与元数据一致），拿不到再用响应头
            val totalBytes = release.sizeBytes.takeIf { it > 0 } ?: connection.contentLengthLong
            val digest = MessageDigest.getInstance(SHA256_ALGORITHM)

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloadedBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
            Downloaded(sha256 = digest.digest().toHex())
        } finally {
            connection.disconnect()
        }
    }

    // ---- 第二道校验：签名 ----

    /**
     * 待装 APK 的签名问题；null 表示校验通过。
     *
     * 比对的是**签名证书的 SHA-256 指纹集合**而不是 `Signature.toCharsString()`：
     * 后者是平台内部编码，跨版本/跨实现不可靠；指纹是标准做法。
     * 同时比对包名 —— 签名一致但包名不同说明装的是另一个应用，同样必须拒绝。
     */
    private fun signatureProblem(apkFile: File): String? {
        @Suppress("DEPRECATION")
        val archiveInfo = packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNATURES,
        ) ?: return UNREADABLE_APK_MESSAGE

        if (archiveInfo.packageName != context.packageName) return PACKAGE_MISMATCH_MESSAGE

        val archiveSignatures = signatureHashes(archiveInfo)
        val currentSignatures = signatureHashes(installedPackageInfo())
        // 读不到签名一律拒绝：无法证明来源就不该安装
        if (archiveSignatures.isEmpty() || currentSignatures.isEmpty()) return UNREADABLE_SIGNATURE_MESSAGE

        // 用"有交集"而不是"完全相等"：同一应用升级前后签名集合是同一个证书；
        // 多签名的包在轮换期可能一侧多一条，交集足以证明是同一发布方
        return if (archiveSignatures.any { hash -> hash in currentSignatures }) {
            null
        } else {
            SIGNATURE_MISMATCH_MESSAGE
        }
    }

    // 一个包的签名证书指纹集合（每个签名一条 sha256 hex）
    private fun signatureHashes(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = info.signatures ?: return emptySet()
        return signatures.mapNotNull { signature ->
            runCatching { sha256Hex(signature.toByteArray()) }.getOrNull()
        }.toSet()
    }

    // ---- 第四步：调起系统安装器 ----

    private fun launchInstaller(apkFile: File): InstallResult {
        val apkInstaller = installer ?: return InstallResult.Failed(NO_INSTALLER_MESSAGE)

        // Android 8.0+ 需要"安装未知应用"授权；没授权时先把用户送到系统设置页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            apkInstaller.openUnknownSourcesSettings()
            return InstallResult.NeedInstallPermission(appLabel())
        }

        return if (apkInstaller.startInstall(apkFile)) {
            InstallResult.Started
        } else {
            // 个别机型没有安装器（或策略禁用），给出可读原因而不是崩溃
            InstallResult.Failed(NO_INSTALLER_MESSAGE)
        }
    }

    private fun appLabel(): String = runCatching {
        context.applicationInfo.loadLabel(packageManager).toString()
    }.getOrDefault(context.packageName)

    private fun installedPackageInfo(): PackageInfo {
        @Suppress("DEPRECATION")
        return packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }

    // 下载落点：cacheDir 下的子目录，免存储权限，安装成功后系统会自己清旧包
    private fun cacheFileFor(release: RemoteRelease): File {
        val directory = File(context.cacheDir, UPDATE_CACHE_DIR_NAME).apply { mkdirs() }
        // 文件名固定：同一时刻只保留一个待装包，避免 cache 里堆积多个版本
        return File(directory, PENDING_APK_FILE_NAME)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance(SHA256_ALGORITHM).digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        val value = byte.toInt() and BYTE_MASK
        HEX_DIGITS[value ushr HEX_SHIFT] + "" + HEX_DIGITS[value and HEX_LOW_MASK]
    }
}

private const val SHA256_ALGORITHM = "SHA-256"
private const val UPDATE_CACHE_DIR_NAME = "updates"
private const val PENDING_APK_FILE_NAME = "lumigather-update.apk"

private const val HTTP_GET = "GET"
private const val HTTP_SUCCESS_RANGE_FIRST = 200
private const val HTTP_SUCCESS_RANGE_LAST = 299
private val HTTP_SUCCESS_RANGE = HTTP_SUCCESS_RANGE_FIRST..HTTP_SUCCESS_RANGE_LAST

// 8MB 的包在局域网/宽带下都不该等太久；超时给 30 秒，比查版本宽松
private const val CONNECT_TIMEOUT_MS = 15_000
// 单次读的间隔上限。慢速链路上每块可能隔很久，30s 会在下载中途误判超时
private const val READ_TIMEOUT_MS = 60_000
private const val BUFFER_SIZE = 64 * 1024

private const val BYTE_MASK = 0xFF
private const val HEX_SHIFT = 4
private const val HEX_LOW_MASK = 0x0F
private const val HEX_DIGITS = "0123456789abcdef"

// 下载源前缀，按尝试顺序。空串表示直连 GitHub —— 放最后：它最稳但最慢。
// 这两个加速器是公开服务，与参考实现同一类方案（镜像优先、GitHub 兜底）。
private val DOWNLOAD_SOURCE_PREFIXES = listOf(
    "https://ghfast.top/",
    "https://gh-proxy.com/",
    "",
)

private const val DOWNLOAD_FAILED_MESSAGE = "下载失败，请检查网络后重试"
private const val NO_INSTALLER_MESSAGE = "没有可用的安装器"
private const val UNREADABLE_APK_MESSAGE = "无法读取安装包信息，已拒绝安装"
private const val PACKAGE_MISMATCH_MESSAGE = "安装包与当前应用不是同一个（包名不一致），已拒绝安装"
private const val UNREADABLE_SIGNATURE_MESSAGE = "无法验证安装包签名，已拒绝安装"

/** 签名不一致的提示：这是安全事件，文案要让人意识到"可能被篡改"而不是"下载出错了"。 */
private const val SIGNATURE_MISMATCH_MESSAGE = "安装包签名与当前应用不一致，可能被篡改，已拒绝安装"

/** SHA-256 不一致的提示：文件损坏或被替换。 */
private const val SHA_MISMATCH_MESSAGE = "安装包校验失败（SHA-256 不一致），已删除并拒绝安装"

/**
 * 调起系统安装器 / 打开「安装未知应用」设置的能力。
 *
 * 为什么做成接口由 app 模块实现：这两件事需要 `androidx.core` 的 FileProvider 与 manifest 里
 * 的 provider 声明，而 shared 模块不依赖 androidx（更新功能不为此引入新依赖）。
 * app 模块在启动时通过 [AppInstallerRegistry] 注册实现即可。
 */
interface ApkInstaller {
    /** 用 content:// URI 调起系统安装器；返回 false 表示没有可用安装器。 */
    fun startInstall(apkFile: File): Boolean

    /** 打开本应用的「安装未知应用」授权页。 */
    fun openUnknownSourcesSettings()
}

/** 安装器实现的注册点（由 app 模块在启动时写入，未注册时更新功能会明确失败而不是崩溃）。 */
object AppInstallerRegistry {
    var installer: ApkInstaller? = null
        private set

    fun install(installer: ApkInstaller) {
        this.installer = installer
    }
}
