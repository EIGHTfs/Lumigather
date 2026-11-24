// 下载保存到系统相册的能力声明。
//
// 为什么单独抽接口 + expect：存相册在 Android 上要走 MediaStore（platform API），
// commonMain 看不到；而页面（commonMain）必须能直接调用它并拿到"成功/失败原因"，
// 所以把平台实现藏在 expect 工厂后面，页面只依赖接口。
//
// 失败为什么分四类而不是只给一句话：四类的补救动作完全不同 ——
// 权限问题要去系统设置开权限，网络问题要检查服务器，存储问题要清空间，
// 未知问题只能重试。UI 要能把用户引到正确的下一步，就不能把它们揉成"保存失败"。

package ui.screens.download

/** 失败原因分类。新增分类时必须同时更新 UI 的提示文案，否则用户又会看到笼统的一句话。 */
enum class DownloadFailureKind {
    PermissionDenied,
    Network,
    Storage,
    Unknown,
}

sealed class DownloadResult {

    /** 已写入相册。 */
    data object Success : DownloadResult()

    /**
     * 相册里已经有同名文件，本次没有重复写入。
     *
     * 单列一类而不是当成功：用户连点两下时，需要知道"没再存一份"而不是以为存了两份。
     */
    data object AlreadySaved : DownloadResult()

    /** 失败。[message] 是可直接上屏的中文原因。 */
    data class Failure(val kind: DownloadFailureKind, val message: String) : DownloadResult()
}

interface ImageDownloader {
    /**
     * 把 [imageUrl] 的字节流保存到系统相册。
     *
     * 实现约定：
     * - 内部自己切后台线程（调用方通常在 UI 协程里直接 await）；
     * - 不抛异常，所有失败都返回 [DownloadResult.Failure]；
     * - 同名文件已存在时返回 [DownloadResult.AlreadySaved]，不重复写。
     */
    suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult
}

// 平台实现由 androidMain 提供（MediaStore）。
expect fun createImageDownloader(): ImageDownloader
