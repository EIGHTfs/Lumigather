// 调起系统安装器（APK 自动更新的最后一步）。
//
// 放在 app 模块而不是 shared：这一步需要 androidx.core 的 FileProvider 与 manifest 里的 provider
// 声明，shared 模块不依赖 androidx —— 更新功能不该为此给 shared 引入新依赖。
// shared 侧的更新状态机通过 ApkInstaller 接口请这里代劳。

package com.eightfs.lumigather

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.eightfs.lumigather.data.ApkInstaller
import java.io.File

class AndroidApkInstaller(private val context: Context) : ApkInstaller {

    override fun startInstall(apkFile: File): Boolean {
        val apkUri = FileProvider.getUriForFile(context, authority(), apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            // 让系统安装器有权读这个 content:// 文件
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从 Application/Service 上下文启动必须 NEW_TASK，否则抛 AndroidRuntimeException
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(installIntent)
            true
        } catch (failure: Throwable) {
            // 个别机型/定制系统没有可用安装器，交给上层给可读提示
            false
        }
    }

    override fun openUnknownSourcesSettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(settingsIntent) }
    }

    // authority 必须与 manifest 里 provider 的 android:authorities 完全一致：
    // 两边都从 applicationId 派生，改包名时不会漏改一处
    private fun authority(): String = "${context.packageName}$FILE_PROVIDER_SUFFIX"
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
