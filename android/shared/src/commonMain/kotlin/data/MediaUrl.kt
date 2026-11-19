// 相对地址 → 绝对地址的拼接规则，集中在这里一处。
//
// 为什么必须集中：后端给的 images[].url 是**相对路径**（"/api/media?file=%2F..."），
// 直接拿去请求会失败；而拼错的后果是"图片全白"，排查时很难看出是拼接问题。
// 把规则收敛成三个纯函数，就能用单测钉死，不依赖网络。
//
// 三条边界（写清楚是为了拦住后来人"顺手加功能"）：
// 1) 不重新编码：relativeUrl 里的 file 参数已被后端 URL 编码过，再编一次会把 %2F 变成 %252F，
//    实测直接变成 404。
// 2) 已是 http(s):// 开头的绝对地址原样返回：后端哪天改成下发绝对地址，客户端不用改。
// 3) 空串进、空串出：UI 拿到空串就知道该画占位，而不是拿一个残缺地址去请求。

package com.eightfs.lumigather.data

private const val SCHEME_HTTP = "http://"
private const val SCHEME_HTTPS = "https://"
private const val PATH_SEPARATOR = "/"
private const val PATH_SEPARATOR_CHAR = '/'

/**
 * 归一化 baseUrl：去掉首尾空白与**末尾斜杠**。
 *
 * 末尾斜杠是拼串时最常见的双斜杠来源（"http://h:8081/" + "/api/x"）。
 * 中间的子路径前缀必须保留（服务端可能部署在 /lumigather 之类前缀下）。
 */
fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.trimEnd(PATH_SEPARATOR_CHAR)
}

/**
 * 把 [relativeOrAbsolute] 接到 [baseUrl] 后面。
 *
 * baseUrl 为空时原样返回入参：此时 UI 应该显示"未配置服务器"，而不是显示一个残缺地址。
 */
fun joinUrl(baseUrl: String, relativeOrAbsolute: String): String {
    if (relativeOrAbsolute.isBlank()) return ""
    if (relativeOrAbsolute.startsWith(SCHEME_HTTP) || relativeOrAbsolute.startsWith(SCHEME_HTTPS)) {
        return relativeOrAbsolute
    }
    val base = normalizeBaseUrl(baseUrl)
    if (base.isEmpty()) return relativeOrAbsolute
    return if (relativeOrAbsolute.startsWith(PATH_SEPARATOR)) {
        base + relativeOrAbsolute
    } else {
        base + PATH_SEPARATOR + relativeOrAbsolute
    }
}

/**
 * 取某张图可直接加载的绝对地址。
 *
 * 这个函数只是 [joinUrl] 的语义别名：让调用点读起来是"要媒体地址"，而不是"拼一次 URL"。
 * 真正给 UI 用的值已经由仓库层写进 [ImageItem.absoluteUrl]，这里保留给自检与兜底场景。
 */
fun mediaUrlOf(baseUrl: String, item: ImageItem): String = joinUrl(baseUrl, item.relativeUrl)
