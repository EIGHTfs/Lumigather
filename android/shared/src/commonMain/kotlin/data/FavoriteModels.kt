// 收藏与游客上传的数据模型。
//
// 这两个接口返回的都是**裸数组**（`[ {...}, {...} ]`），不是 `{ items: [...] }` 那种包装 ——
// 按习惯去取 .items 会直接解析失败，所以解析入口在 ApiClient 里单独开了「数组版」通道。
//
// src 一律在解析时用 MediaSrc.normalize 规范化后保存：收藏的比对与去重全依赖它，
// 保留原始写法会让"同一张图两种地址"的问题一直存在。

package com.eightfs.lumigather.data

/** 一条收藏。字段与后端 POST 的 body 一一对应，`src` 已规范化。 */
data class FavoriteEntry(
    val src: String = "",
    val name: String = "",
    val title: String = "",
    val cat: String = "",
    val dir: String = "",
    val size: String = "",
    val root: String = "",
    val addedAt: Long = 0L,
)

/** 一条游客上传。`url` 与 `src` 在服务端都是相对地址（`/uploads/xxx`）。 */
data class UploadedEntry(
    val id: String = "",
    val src: String = "",
    val url: String = "",
    val name: String = "",
    val title: String = "",
)

/** 添加收藏的请求体。字段名与后端 readJsonBody 后取的键逐字一致。 */
data class FavoriteRequest(
    val src: String,
    val name: String = "",
    val title: String = "",
    val cat: String = "",
    val dir: String = "",
    val size: String = "",
    val root: String = "",
)

/**
 * 添加收藏的结果。
 *
 * [alreadyExists] 单独留一个字段而不是当失败：用户点收藏时它已经在服务端了（另一台设备加过、
 * 或本地列表过期），这既不是错误也不该提示"失败"，界面上只要把状态刷成"已收藏"即可。
 */
data class FavoriteMutation(
    val alreadyExists: Boolean = false,
    val favorites: List<FavoriteEntry> = emptyList(),
)

/**
 * 收藏条目 → 网格/详情栏能直接用的图片模型。
 *
 * [absoluteUrl] 由调用方传入（拼接规则在 ApiClient，数据层不持有 baseUrl）：
 * 这样 UI 拿到的就是"可直接加载"的地址，不必自己拼 —— 与图片列表的契约保持一致。
 * 收藏没有"修改时间"（只有 addedAt 时间戳），所以 modifiedIso 留空，详情页会显示占位符。
 */
fun FavoriteEntry.toImageItem(absoluteUrl: String): ImageItem = ImageItem(
    name = name.ifBlank { title },
    path = if (dir.isBlank()) name else "$dir/$name",
    relativeUrl = src,
    sizeText = size,
    modifiedIso = "",
    absoluteUrl = absoluteUrl,
)

/** 游客上传条目 → 图片模型。上传项的 url 本身就是可加载的相对地址（`/uploads/xxx`）。 */
fun UploadedEntry.toImageItem(absoluteUrl: String): ImageItem = ImageItem(
    name = name.ifBlank { id },
    path = src,
    relativeUrl = url.ifBlank { src },
    sizeText = "",
    modifiedIso = "",
    absoluteUrl = absoluteUrl,
)
