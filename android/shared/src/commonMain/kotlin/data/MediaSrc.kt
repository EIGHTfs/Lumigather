// 媒体地址规范化：让「绝对地址」和「相对地址」变成同一种可比对的形式。
//
// 为什么必须做：收藏数据里旧的 src 可能存的是完整 URL（`http://host:8081/api/media?file=...`），
// 服务端换端口或用户换地址后，同一个文件就有了两种写法 —— 直接按字符串比对会把它当成两张图，
// 于是"明明收藏过却显示未收藏"，再点一次就又多一条收藏。
//
// 规则很简单：只保留 pathname + search，丢掉 scheme / host / port。
// `http://h:8081/api/media?file=%2Fa.png` → `/api/media?file=%2Fa.png`
// 已经是相对路径的原样（补一个前导斜杠），空串仍然是空串。
//
// 放在数据层而不是 UI：数据层解析收藏列表时要规范化一次，状态层比对"这张图收没收藏"又要用一次，
// 两边必须是同一份实现 —— 两处各写一份迟早会出现"解析时规范了、比对时没规范"的错配。

package com.eightfs.lumigather.data

object MediaSrc {

    private const val SCHEME_SEPARATOR = "://"
    private const val PATH_SEPARATOR = "/"

    /** 规范化一个媒体地址；无法解析时返回空串（调用方应把它当作"没有有效地址"）。 */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val schemeIndex = trimmed.indexOf(SCHEME_SEPARATOR)
        if (schemeIndex < 0) {
            // 已经是相对路径（服务端下发的就是这个形态），只保证有前导斜杠
            return if (trimmed.startsWith(PATH_SEPARATOR)) trimmed else PATH_SEPARATOR + trimmed
        }

        val afterScheme = trimmed.substring(schemeIndex + SCHEME_SEPARATOR.length)
        val pathIndex = afterScheme.indexOf(PATH_SEPARATOR)
        // "http://host" 这种没有路径的地址不指向任何文件，按空串处理
        if (pathIndex < 0) return ""
        return afterScheme.substring(pathIndex)
    }

    /**
     * 两个地址是否指向同一个媒体。
     *
     * 空串一律不算"相同"：两个都解析失败时相等没有意义，把"未知"当成"已收藏"会让用户点不掉。
     */
    fun isSame(first: String, second: String): Boolean {
        val normalizedFirst = normalize(first)
        val normalizedSecond = normalize(second)
        return normalizedFirst.isNotEmpty() && normalizedFirst == normalizedSecond
    }
}
