// src 规范化的单测：收藏的比对与去重全靠这一个函数，错一位就会"收藏过却显示未收藏"。
//
// 这里只测纯函数本身（不构造 holder）：它是这条链路上唯一与 UI/网络无关、可以被穷举验证的一环。

package com.eightfs.lumigather.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaSrcTest {

    @Test
    fun absoluteUrlKeepsOnlyPathAndQuery() {
        assertEquals(
            "/api/media?file=%2Fa%2Fb.png",
            MediaSrc.normalize("http://any-host:9999/api/media?file=%2Fa%2Fb.png"),
        )
    }

    @Test
    fun relativeUrlIsKeptAndGainsLeadingSlash() {
        assertEquals("/api/media?file=%2Fa.png", MediaSrc.normalize("/api/media?file=%2Fa.png"))
        assertEquals("/api/media?file=%2Fa.png", MediaSrc.normalize("api/media?file=%2Fa.png"))
    }

    @Test
    fun sameFileInTwoFormsComparesEqual() {
        assertTrue(
            MediaSrc.isSame(
                "http://server.test/api/media?file=%2Froot%2Fa.png",
                "/api/media?file=%2Froot%2Fa.png",
            ),
        )
        // 换端口也还是同一张图（host/port 被丢掉）
        assertTrue(
            MediaSrc.isSame(
                "http://other-host:9999/api/media?file=%2Fa.png",
                "/api/media?file=%2Fa.png",
            ),
        )
    }

    @Test
    fun differentFilesAreNotSame() {
        assertFalse(MediaSrc.isSame("/api/media?file=%2Fa.png", "/api/media?file=%2Fb.png"))
    }

    @Test
    fun emptyOrHostlessInputsAreNotComparable() {
        assertEquals("", MediaSrc.normalize(""))
        assertEquals("", MediaSrc.normalize("   "))
        assertEquals("", MediaSrc.normalize("http://server.test"))
        // 两个都解析失败时不能算"相同"，否则"未知"会被当成"已收藏"而点不掉
        assertFalse(MediaSrc.isSame("", ""))
    }
}
