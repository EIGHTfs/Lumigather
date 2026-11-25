// 压缩包与序列帧的数据模型 + 少量纯函数判据。
//
// 客户端的边界（这条线必须守住）：**不解压、不编码 GIF、不解析 ini**。
// 包内图片由服务端从压缩包里流出来（7zz），GIF 由服务端 encoder 生成，
// 3DMigoto 的 180° 翻转与 ini `$speed` 换算全在 server/lib/ 里。
// 客户端只做两件事：把参数拼成 URL、把结果显示出来。

package com.eightfs.lumigather.data

// GIF 合成用的游戏渲染帧率：服务端只在"用 ini 的 $speed 换算帧间隔"时才读它，默认 60。
// 客户端不做换算，原样传给服务端 —— 这个常量只是"请求参数自解释"用的默认值。
const val DEFAULT_GIF_FPS = 60

// dur_ms = 0 表示"帧间隔由服务端决定"（包名带 @40ms 这类间隔时服务端会自己采用）
const val GIF_AUTO_DURATION_MS = 0

/**
 * 一个压缩包（GET /api/zip/scan 的 zips[] 元素）。
 *
 * @param images 包内图片条目名（未 URL 编码；拼 URL 时再编码）
 * @param hasIni 含 .ini = 3DMigoto mod。注意它只决定"这个包是 mod 包"的展示提示，
 *   到底哪张图要翻转由服务端 flip-rule 逐图判定（包内可能只有 desktop.ini 这类元数据 ini）
 * @param speed ini 里声明的 $speed（仅 hasIni 时可能有值）；客户端不拿它算帧间隔，
 *   只在界面上作为"动画倍速"的说明信息展示
 */
data class ArchiveEntry(
    val path: String = "",
    val name: String = "",
    val images: List<String> = emptyList(),
    val hasIni: Boolean = false,
    val speed: Double? = null,
)

/**
 * 序列帧判据：目录/包内的图片名看起来像不像"同一套动画的连续帧"。
 *
 * 为什么客户端也要判一次（而服务端也能判）：服务端在拿到请求时才知道，而我们要决定
 * **要不要显示「合成动图」这个入口** —— 每目录都摆一个按钮，用户点进去九成是 400，很难看。
 *
 * 判据刻意宽松（只用来决定按钮显不显示，不决定成败）：≥2 张图片，且至少两张去掉末尾数字后同名前缀。
 * 真正能不能合成由服务端说了算，客户端判错最多是多/少一个按钮，不会给出错误结果。
 */
object ArchiveHeuristics {

    private const val MIN_SEQUENCE_SIZE = 2
    private const val DIGIT_END_PATTERN = "\\d+$"

    fun looksLikeSequence(fileNames: List<String>): Boolean {
        if (fileNames.size < MIN_SEQUENCE_SIZE) return false
        // 必须先剥扩展名再剥尾部数字：`frame001.png` 的末尾是 "png"，直接剥数字会剥不掉，
        // 结果每张图前缀都不同，序列帧永远判不出来（这是第一版实现踩过的坑）。
        val prefixed = fileNames.groupBy { name ->
            name.substringBeforeLast('.').replace(Regex(DIGIT_END_PATTERN), "")
        }
        return prefixed.any { (prefix, group) ->
            // 前缀为空（文件名全是数字）也算一组；但要求组内至少两张
            prefix.isNotEmpty() && group.size >= MIN_SEQUENCE_SIZE
        }
    }

    /**
     * 条目名是否安全。含 `..` 的条目名服务端会直接 400（防路径穿越），
     * 客户端提前过滤掉，免得网格里挂一堆必然失败的图。
     */
    fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.contains("..")
}
