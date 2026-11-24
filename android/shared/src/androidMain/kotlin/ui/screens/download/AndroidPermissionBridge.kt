// 运行时权限桥：把 Activity 的"申请 → 结果回传"包成一次 suspend 调用。
//
// 为什么需要它：Android 的权限申请是异步回调模型（requestPermissions → onRequestPermissionsResult），
// 而下载逻辑写成 suspend 最自然。这里用挂起 + 续体把两者接起来，调用点就能线性地
// "问权限 → 拿结果 → 继续下载"，不用把下载状态机拆成一堆回调。
//
// 只处理我们自己发的请求码：结果回来先比对 requestCode，不匹配就还给宿主，避免抢别人的回调。

package ui.screens.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import ui.screens.settings.AndroidHostContext
import kotlin.coroutines.resume

object AndroidPermissionBridge {

    // 请求码由本桥独占。宿主里若还有别的权限申请，必须用不同的码。
    const val REQUEST_CODE_STORAGE = 1001

    // 等待中的续体。只可能有一个（下载入口是单按钮、且下载中禁用），故不引入队列。
    private var pendingContinuation: ((Boolean) -> Unit)? = null

    private val storagePermission: String
        get() = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /**
     * 判断存储权限是否已授予。
     *
     * 只有 API 28 及以下需要它；29+ 走 Scoped Storage，写相册不需要权限，
     * 所以调用方用 [ensureWriteStoragePermission] 即可，不用自己判版本。
     */
    fun hasWriteStoragePermission(): Boolean {
        // M 以下（本工程 minSdk 24，走不到这里）安装即授予，视为有权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val context = AndroidHostContext.current() ?: return false
        return context.checkSelfPermission(storagePermission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 确保有写存储权限：已授予直接返回 true；未授予则弹系统对话框并等结果。
     *
     * 拿不到 Activity（宿主没装）或用户拒绝都返回 false —— 调用方据此给出
     * "去系统设置里打开"这类可操作提示，绝不静默。
     */
    suspend fun ensureWriteStoragePermission(): Boolean {
        if (hasWriteStoragePermission()) return true
        val activity = AndroidHostContext.currentActivity() ?: return false

        return suspendCancellableCoroutine { continuation ->
            pendingContinuation = { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
            // 用户可能把对话框划走：协程被取消时清掉悬着的续体，
            // 否则下一次申请会覆盖它、上一次永远挂着（表现为"点了没反应"）。
            continuation.invokeOnCancellation { pendingContinuation = null }
            activity.requestPermissions(arrayOf(storagePermission), REQUEST_CODE_STORAGE)
        }
    }

    /**
     * 宿主 Activity 在 onRequestPermissionsResult 里转发过来。
     *
     * @return true 表示这次结果属于本桥（宿主不必再往上报）
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE_STORAGE) return false
        val callback = pendingContinuation ?: return true
        pendingContinuation = null
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        callback(granted)
        return true
    }
}
