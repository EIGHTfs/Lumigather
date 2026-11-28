// 媒体地址比对。
//
// 历史：这里曾有一个 normalize()，把「绝对地址」与「相对地址」统一成 pathname + search，
// 用来消化旧收藏数据里存过完整 URL（`http://host:8081/api/media?file=...`）的情况。
//
// 现在它被移到了服务端（`server/routes/favorites.js` 的 normalizeMediaSrc）：
// 收藏的查重与删除都在写入侧规范化，存进 favorites.json 的一律是相对路径。
// 实测确认：2026-11-28 线上 192 条收藏全部为相对路径，无一条带 host。
//
// 为什么不再放客户端：每个客户端各写一份必然漂移（本项目就是先有客户端版、
// 而服务端仍原样存原文），而「同一份数据的比对规则」只应该有一个权威实现。
// 客户端现在只负责比对，不负责修补数据形态。

package com.eightfs.lumigather.data

object MediaSrc {

    /**
     * 两个地址是否指向同一个媒体。
     *
     * 空串一律不算"相同"：两个都为空时相等没有意义，把"未知"当成"已收藏"会让用户点不掉。
     * 服务端下发的 src 已是相对路径，这里直接比字符串即可。
     */
    fun isSame(first: String, second: String): Boolean =
        first.isNotEmpty() && first == second
}
