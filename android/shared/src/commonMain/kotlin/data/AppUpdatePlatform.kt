// APK 更新里与"平台"相关的两件事：读本地版本、下载并校验后调起安装器。
//
// 为什么用接口而不是把下载逻辑写进状态层：这些能力全都需要 Android Context
// （PackageManager 读签名、FileProvider 生成 content://、Intent 调安装器），
// 而状态层跑在 commonMain。抽出接口后：
//   - 状态层只表达"检查/下载/校验/安装"四个阶段，能纯逻辑单测（注入假实现即可）
//   - 平台实现独立演进，不影响状态机
//   - 单测里能构造"签名不匹配"这种现实中很难复现的场景（真 APK 得重签才做得到）

package com.eightfs.lumigather.data

interface AppUpdatePlatform {
    /** 当前已安装的 versionCode（运行时从 PackageManager 读，不写死）。 */
    fun localVersionCode(): Int

    /** 当前已安装的 versionName，给界面显示"当前版本 x.y.z"。 */
    fun localVersionName(): String

    /**
     * 下载 → 校验（SHA-256 + 签名）→ 调起系统安装器。
     *
     * @param onProgress 已下载字节 / 总字节；总字节取值的优先级是
     *   「远端声明的 size」→「HTTP 响应头 Content-Length」，两者都没有时给 0
     *
     * 约定：**校验不通过必须删除已下载文件**，不能留下一个看起来能装的包；
     * 校验结果用 [InstallResult.Rejected] 表达，不要归到 [InstallResult.Failed]。
     */
    suspend fun downloadVerifyAndInstall(
        release: RemoteRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): InstallResult
}

/** 平台实现由各平台提供（Android：OkHttp 下载 + PackageManager 签名比对 + FileProvider）。 */
expect fun createAppUpdatePlatform(): AppUpdatePlatform
