package ui.blocks

import com.tencent.kuikly.compose.ui.graphics.Color

/**
 * 把服务端下发的十六进制色串（形如 `#c084fc`）转成颜色对象。
 *
 * 为什么在 UI 层做这件事：数据层刻意不解析颜色（[com.eightfs.lumigather.data.Category.color]
 * 保持原始字符串），颜色属于表现层；解析放在这里，数据层就不必依赖任何 UI 类型。
 *
 * 解析失败绝不能抛异常：脏数据（空串、`red`、`#GGG`）只应让这个分类退化成主色，
 * 而不是把整个分类列表带崩。所以任何异常路径都返回 [fallback]。
 */
internal fun colorFromHex(hex: String, fallback: Color): Color {
    val cleaned = hex.removePrefix(HEX_PREFIX).trim()
    if (cleaned.length != HEX_RGB_LENGTH) return fallback

    val red = cleaned.substring(0, HEX_CHANNEL_LENGTH).toIntOrNull(HEX_RADIX) ?: return fallback
    val green = cleaned.substring(HEX_CHANNEL_LENGTH, HEX_CHANNEL_LENGTH * 2).toIntOrNull(HEX_RADIX) ?: return fallback
    val blue = cleaned.substring(HEX_CHANNEL_LENGTH * 2, HEX_CHANNEL_LENGTH * 3).toIntOrNull(HEX_RADIX) ?: return fallback

    return Color(red, green, blue, OPAQUE_ALPHA)
}

/** 六位 RGB（不含 alpha）的长度 */
private const val HEX_RGB_LENGTH = 6

/** 单个颜色通道在串里占两个字符 */
private const val HEX_CHANNEL_LENGTH = 2

private const val HEX_RADIX = 16

/** 不透明：服务端给的分类色都是实色，没有 alpha 通道 */
private const val OPAQUE_ALPHA = 255

/** 服务端色串的井号前缀。写成常量是为了让「解析时要剥掉哪个字符」这件事只有一处定义 */
private const val HEX_PREFIX = "#"
