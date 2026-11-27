// 版本号解析与比较（更新判断的唯一入口）。
//
// 为什么单独成文件：这段逻辑有明确的边界（tag 可以写成任何样子）与单一职责（把 tag 变成可比较的整数），
// 而且是最容易出错、最需要单测钉死的一处。它也是"版本比较"这件事在代码里唯一的实现，
// 页面与状态层都必须经过它，不允许各自写一份 `tagName > local` 之类的字符串比较。

package com.eightfs.lumigather.data

object VersionTag {

    private const val VERSION_SEPARATOR = '-'
    private const val PREFIX_V_LOWER = "v"
    private const val PREFIX_V_UPPER = "V"

    /**
     * 从发布 tag 里取 versionCode。
     *
     * 规则：取**最后一个** `-` 之后的部分转整数（`0.0.2-1125` → 1125）。
     * 为什么是最后一个而不是第一个：语义版本可能带预发布后缀（`0.0.3-rc1-1200`），
     * 版本号总在最后一段。
     *
     * 返回 null 的三种情况都表示"无法比较"，调用方要给可读提示，不能当成"没有新版本"：
     * - 没有数字段（`0.0.2`、`latest`）
     * - 数字段里混了非数字（`1125-hotfix`）
     * - 数字超出 Int 范围（越界会被 [toIntOrNull] 挡成 null）
     *
     * 额外容忍前导 `v`（`v0.0.2-1125`），因为 tag 常见这么写。
     */
    fun parseVersionCode(tagName: String): Int? {
        var text = tagName.trim()
        if (text.isEmpty()) return null
        if (text.startsWith(PREFIX_V_LOWER) || text.startsWith(PREFIX_V_UPPER)) {
            text = text.substring(1)
        }
        if (text.isEmpty()) return null

        val separatorIndex = text.lastIndexOf(VERSION_SEPARATOR)
        val codePart = if (separatorIndex >= 0) text.substring(separatorIndex + 1) else text
        if (codePart.isEmpty()) return null
        // 必须整段都是数字：`1125-beta` 这种无法安全比较，直接判为不可比较
        if (codePart.any { symbol -> symbol !in '0'..'9' }) return null
        return codePart.toIntOrNull()
    }

    /**
     * 远端是否比本地新。
     *
     * 用整数 versionCode 比较而不是 versionName 字符串：`0.0.10-1130` 与 `0.0.9-1129` 按字符串
     * 比较会得出错误结论（`0.0.1` < `0.0.9` 但 `0.0.10` 字符上更小）。
     * 远端版本号解析不出（null）时一律不算有更新 —— 降级安装会被系统拒绝，多这一次没有意义。
     */
    fun isNewer(remoteVersionCode: Int?, localVersionCode: Int): Boolean =
        remoteVersionCode != null && remoteVersionCode > localVersionCode
}
