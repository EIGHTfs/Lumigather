// 媒体地址比对。
//
// 历史：这里原本还测过 normalize()（剥 scheme/host、补前导斜杠），
// 那是为旧收藏数据里带 host 的 src 做兜底。该职责已移到服务端
// （server/routes/favorites.js 的 normalizeMediaSrc，写入侧统一规范化），
// 客户端现在只做比对，所以那些归一化用例随实现一起删除。
// 实测依据：2026-11-28 线上 192 条收藏全部为相对路径，无一条带 host。

package com.eightfs.lumigather.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaSrcTest {

    @Test
    fun sameRelativeSrcComparesEqual() {
        // 服务端下发的 src 一律是相对形态，直接字符串相等即为同一张图
        assertTrue(
            MediaSrc.isSame(
                "/api/media?file=%2Fvolume6%2Fa%2Fb.png",
                "/api/media?file=%2Fvolume6%2Fa%2Fb.png",
            ),
        )
    }

    @Test
    fun differentFilesAreNotSame() {
        assertFalse(
            MediaSrc.isSame(
                "/api/media?file=%2Fa.png",
                "/api/media?file=%2Fb.png",
            ),
        )
    }

    @Test
    fun emptySrcIsNeverSame() {
        // 两个都为空时"相等"没有意义：把"未知"当成"已收藏"会让用户点不掉收藏
        assertFalse(MediaSrc.isSame("", ""), "空串不能算同一张图")
        assertFalse(MediaSrc.isSame("", "/api/media?file=%2Fa.png"))
        assertFalse(MediaSrc.isSame("/api/media?file=%2Fa.png", ""))
    }
}
