// 千分位格式化的离线测试。
//
// 为什么单独测它：这个函数决定分类栏每一行右侧胶囊里的文字。它错的表现很隐蔽 ——
// 少一个逗号或负数符号跑错位，视觉上"看着还行"，只有对着具体数字才能发现；
// 而 72,651 这种真实数据在真机上要滚到对应分类才看得到，排查成本高。
// 边界值（Int.MIN_VALUE）尤其容易写错：直接 -value 会溢出回自身，必须以 Long 中转。

package ui.blocks

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryBlockFormatTest {

    @Test
    fun zeroStaysSingleDigit() {
        assertEquals("0", formatCountThousands(0))
    }

    @Test
    fun belowThousandHasNoSeparator() {
        assertEquals("1", formatCountThousands(1))
        assertEquals("999", formatCountThousands(999))
    }

    @Test
    fun thousandsGetOneSeparator() {
        assertEquals("1,000", formatCountThousands(1000))
        assertEquals("1,262", formatCountThousands(1262))
        assertEquals("72,651", formatCountThousands(72651))
        assertEquals("999,999", formatCountThousands(999999))
    }

    @Test
    fun millionsGetTwoSeparators() {
        assertEquals("1,234,567", formatCountThousands(1234567))
    }

    @Test
    fun negativeKeepsSignBeforeGrouping() {
        assertEquals("-1,262", formatCountThousands(-1262))
    }

    @Test
    fun intMaxValueDoesNotOverflow() {
        assertEquals("2,147,483,647", formatCountThousands(Int.MAX_VALUE))
    }

    // Int.MIN_VALUE 取负会溢出回自身（-(-2147483648) == -2147483648），
    // 必须以 Long 中转；这条用例就是为了钉住这个坑。
    @Test
    fun intMinValueHandledViaLong() {
        assertEquals("-2,147,483,648", formatCountThousands(Int.MIN_VALUE))
    }
}
