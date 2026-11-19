// 服务器地址的校验与归一化。纯函数、不依赖平台，所以这些规则可以直接被单测与真机共用。
//
// 为什么要归一化而不是"存用户输入的原样"：同一个服务器有太多种写法
// （尾部斜杠、大小写协议头、前后空格），原样存下来会让"看起来配了两次其实同一个地址"
// 变成两条不同配置，后面排查地址问题时永远对不上。
//
// 为什么必须在 UI 前置校验：地址一旦存进持久化，下次冷启动就会直接拿它去请求；
// 存进去一个非法值等于让用户在启动时看到"地址无效"却不知道是自己上次填错的。

package ui.screens.settings

object ServerAddressRules {

    const val SCHEME_HTTP = "http://"
    const val SCHEME_HTTPS = "https://"

    // 空输入的提示：与"格式错误"分开，因为它们的操作不同（一个要填、一个要改）。
    const val MESSAGE_EMPTY = "请先填写服务器地址"

    // 格式错误的提示：把判据（协议头 + 无空格 + 有主机名）一次说清，用户才好自己救。
    const val MESSAGE_INVALID = "地址需以 http:// 或 https:// 开头，包含主机名且不含空格"

    private const val PATH_SEPARATOR = '/'
    private const val SPACE = ' '

    /**
     * 把用户输入变成可用的 baseUrl；非法返回 null。
     *
     * 归一化内容：去首尾空白 → 协议头小写 → 去尾部斜杠。
     * 保留内容：主机名后的子路径（服务端可能部署在 /lumigather 这类前缀下）。
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val lowerCased = trimmed.lowercase()
        val scheme = when {
            lowerCased.startsWith(SCHEME_HTTP) -> SCHEME_HTTP
            lowerCased.startsWith(SCHEME_HTTPS) -> SCHEME_HTTPS
            // 没有协议头一律拒绝：补一个默认协议会掩盖"用户以为填的是域名"这类误填
            else -> return null
        }

        val withoutScheme = trimmed.substring(scheme.length).trimEnd(PATH_SEPARATOR)
        if (withoutScheme.isEmpty()) return null
        if (withoutScheme.contains(SPACE)) return null

        // 主机名 = 第一个斜杠之前的部分；为空说明是 "http:///path" 这种没有主机的地址
        val host = withoutScheme.substringBefore(PATH_SEPARATOR)
        if (host.isEmpty()) return null

        return scheme + withoutScheme
    }

    // UI 直接拿来上屏的一句提示：空串给"请填写"，非法给格式要求。
    fun errorMessageFor(raw: String): String =
        if (raw.isBlank()) MESSAGE_EMPTY else MESSAGE_INVALID
}
