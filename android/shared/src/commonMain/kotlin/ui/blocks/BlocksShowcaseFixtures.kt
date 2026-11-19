package ui.blocks

import com.eightfs.lumigather.data.ImageItem

/**
 * 预览用假数据（fixture）：只服务 [BlocksShowcase]，不参与任何业务路径。
 *
 * 为什么单独一个文件：验收口径要求 `ui/blocks/` 不出现样式色值，而分类色是**数据** ——
 * 这些 `#rrggbb` 串照抄服务端 `/api/lumigather` 的 `categories[].color`（真实八个色值里的五个），
 * 与 `AppChip(dotColor)` 同属设计规范 7.3 的「数据即样式」例外。集中到这一个文件后，
 * 除它之外整个 blocks 目录没有任何 `#` 串，评审时一眼能分清「样式」与「数据」。
 */
internal object CategoryColorFixture {
    const val HONKAI_3 = "#c084fc"
    const val GENSHIN = "#4ade80"
    const val STAR_RAIL = "#f472b6"
    const val ZENLESS = "#60a5fa"
    const val WUTHERING = "#fbbf24"
}

/**
 * 预览用的媒体地址：指向文档里记录的实测实例。
 * 没连那台机器时这张图会走失败分支 —— 正好验证「失败只影响本格」。
 */
internal const val SAMPLE_MEDIA_URL =
    "http://10.10.31.59:8081/api/media?file=%2Fvolume6%2FGame.Patch%20N%20MOD%2FmiHoYo%2Floading-logo.png"

/** 故意指向不存在的路径，用来演示失败态（真实坏图在运行时会走到同一分支）。 */
internal const val SAMPLE_BROKEN_URL = "http://10.10.31.59:8081/api/media?file=%2Fnonexistent.png"

/**
 * 假图片项：字段与真实 `images[]` 逐一对齐（含长路径与 ISO 时间），
 * 这样「格式从文件名推」「修改时间只取日期」两个推导逻辑在预览里就能被验证。
 */
internal val SAMPLE_IMAGE = ImageItem(
    name = "loading-logo.f0c3ebe.png",
    path = "<存储根>/Game.Patch N MOD/[miHoYo]/Honkai Impact 3 – 崩坏3/Mods/.Mods/(gamebanana)/loading-logo.f0c3ebe.png",
    relativeUrl = "/api/media?file=%2Fvolume6%2FGame.Patch%20N%20MOD%2FmiHoYo%2Floading-logo.png",
    sizeText = "2.4 MB",
    modifiedIso = "2026-03-15T10:20:30Z",
    absoluteUrl = SAMPLE_MEDIA_URL,
)
