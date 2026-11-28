// 压缩包的数据模型。
//
// 客户端的边界（这条线必须守住）：**不解压、不编码 GIF、不解析 ini、不判断该不该合成动图**。
// 包内图片由服务端从压缩包里流出来（7zz），GIF 由服务端 encoder 生成，
// 序列帧识别（server/lib/rule/gif-rule.js）、3DMigoto 的 180° 翻转与 ini `$speed` 换算全在服务端。
// 客户端只做两件事：把条目名拼成 URL、把图显示出来。

package com.eightfs.lumigather.data

/**
 * 一个压缩包（GET /api/zip/scan 的 zips[] 元素）。
 *
 * @param images 包内图片条目名（未 URL 编码；拼 URL 时再编码）
 * @param hasIni 含 .ini = 3DMigoto mod。注意它只决定"这个包是 mod 包"的展示提示，
 *   到底哪张图要翻转由服务端 flip-rule 逐图判定（包内可能只有 desktop.ini 这类元数据 ini）
 * @param speed ini 里声明的 $speed（仅 hasIni 时可能有值）；客户端不拿它算帧间隔，
 *   只在界面上作为"动画倍速"的说明信息展示
 */
/**
 * 包内一张图片。
 *
 * 服务端 `/api/zip/scan` 的 `images` 是**对象数组**（`server/lib/archive.js:203`）：
 * `entries.filter(...).map(e => ({ name: e.name, size: e.uncompSize }))`。
 * 不是字符串数组 —— 按字符串解析会拿到一串空串，之后拼出来的 `/api/zip/img?file=` 全空，
 * 表现就是"压缩包里的图全都不加载"。（这个坑真踩过：fixture 自己编成字符串数组，测试全绿。）
 *
 * @param name 包内相对路径（可含子目录，如 `雷电芽衣（中）/frame0.png`），直接作为 file 参数
 * @param sizeBytes 解压后大小；0 表示服务端没给
 */
data class ArchiveImage(
    val name: String = "",
    val sizeBytes: Long = 0L,
)

data class ArchiveEntry(
    val path: String = "",
    val name: String = "",
    val images: List<ArchiveImage> = emptyList(),
    val hasIni: Boolean = false,
    val speed: Double? = null,
)

