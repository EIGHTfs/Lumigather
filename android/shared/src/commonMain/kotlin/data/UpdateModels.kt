// APK 自动更新用到的远端模型与安装结果。
//
// 更新源是 GitHub Releases（不经过 Lumigather 服务端）：手机只要能上网就能查版本，
// 也避免把 8MB 的 APK 塞进服务端仓库（那是 public 仓库，二进制不该进 git）。

package com.eightfs.lumigather.data

/**
 * 远端最新发布。
 *
 * @param versionName 发布 tag，形如 `0.0.2-1125`
 * @param versionCode 从 tag 解析出的数字部分（1125）；解析不出时为 null ——
 *   宁可不比较也不要抛异常：tag 是人起的，格式写错不该让"检查更新"整条挂掉
 * @param sha256 asset 的 digest 字段里带的 sha256（GitHub 不一定给，给了才校验）
 */
data class RemoteRelease(
    val versionName: String,
    val versionCode: Int?,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String?,
    val releaseNotes: String,
    val publishedAt: String,
)

/** 下载 + 校验 + 调起安装器 的结果。 */
sealed interface InstallResult {
    /** 已调起系统安装界面（用户还要在系统界面点确认，无法静默安装）。 */
    data object Started : InstallResult

    /** 缺「安装未知应用」权限，需要引导用户去系统设置。 */
    data class NeedInstallPermission(val appLabel: String) : InstallResult

    /**
     * 校验不通过，拒绝安装。
     *
     * 单独成一个结果而不是 [Failed]：这是安全事件（可能被篡改），文案要更严厉、
     * 且必须保证文件已被删除，不能留下一个"看起来能装"的包。
     */
    data class Rejected(val message: String) : InstallResult

    /** 其他失败（网络中断、磁盘满、无安装器）。 */
    data class Failed(val message: String) : InstallResult
}
