// 数据层对外暴露的全部模型。
//
// 两条设计依据，写在这里免得后来人"顺手"改掉：
// 1) 每个字段都必须带默认值。后端存在"降级响应"：请求一个不存在的路径时它回
//    {"path":..., "count":0, "images":[]}，缺 root/total/offset/limit/returned/recursive
//    六个字段。字段不给默认值，一次正常返回就会被当成解析失败。
// 2) 模型里不出现任何 UI 概念（颜色值、像素、组件都不该进来）。UI 要什么表现由 UI 层决定，
//    数据层只保证"后端说了什么，这里就有类型安全的对应物"。

package com.eightfs.lumigather.data

// 一页拉多少张。20 与服务端 imagesPerRow 同量级，也是一屏能看满的密度。
const val DEFAULT_PAGE_SIZE = 20

// ISO 时间串里日期部分的长度："2023-08-18T06:56:03.097Z" 取前 10 位即 2023-08-18。
private const val ISO_DATE_PART_LENGTH = 10

// 扩展名截取时用到的分隔符：取最后一个点之后的内容作为"格式"展示。
private const val EXTENSION_SEPARATOR = '.'

/**
 * 站点配置里的一个分类（对应 GET /api/lumigather 的 categories[]）。
 *
 * [color] 是后端下发的十六进制字符串（形如 "#c084fc"），数据层不解析它：
 * 把字符串转成颜色对象是 UI 主题层的职责。
 */
data class Category(
    val name: String = "",
    val label: String = "",
    val color: String = ""
)

/**
 * 分类到绝对路径的映射（对应 directories[]）。
 *
 * [path] 就是后面请求 /api/images 时要传的 root 参数；[category] 与 [Category.name] 相等。
 * 这张映射表是"每个分类看到自己的图"的唯一依据 —— 不传 root 时后端会回落到全局 fsRoot，
 * 于是 8 个分类全变成同一批图。
 */
data class DirectoryRef(
    val path: String = "",
    val category: String = ""
)

/**
 * GET /api/lumigather 的响应。
 *
 * 只声明主流程会用到的字段：favicon / brand / autoUpdate 故意不建模。
 * autoUpdate 里带着明文 githubToken，一旦建模就会顺着序列化、日志、崩溃栈扩散出去。
 */
data class SiteConfig(
    val title: String = "",
    val fsRoot: String = "",
    val fsRoots: List<String> = emptyList(),
    val imagesPerRow: Int = DEFAULT_PAGE_SIZE,
    val categories: List<Category> = emptyList(),
    val directories: List<DirectoryRef> = emptyList()
)

/**
 * GET /api/directories?root=... 的响应。
 *
 * 字段陷阱：[limitPath] 对应的后端字段名是 limit，但值是**路径字符串**（请求路径被夹取后的根），
 * 不是数量。名字照抄 limit 会让人误以为是分页条数，因此这里改名。
 * [items] 是纯名字数组（没有子项对象），子项是否可展开要再发一次请求才知道。
 */
data class DirectoryListing(
    val root: String = "",
    val items: List<String> = emptyList(),
    val limitPath: String = "",
    val clamped: Boolean = false,
    val canUp: Boolean = false
)

/**
 * 一张图片（对应 images[]）。
 *
 * [relativeUrl] 是后端 url 字段的原始值，形如 "/api/media?file=%2F..."，是**相对路径且已做过
 * URL 编码**。所以：拼绝对地址只能在 baseUrl 前面补，绝不能重新编码 file 参数（会变双重编码）。
 * [absoluteUrl] 由仓库层填好后交给 UI，UI 不必也不应该自己拼。
 */
data class ImageItem(
    val name: String = "",
    val path: String = "",
    val relativeUrl: String = "",
    val sizeText: String = "",
    val modifiedIso: String = "",
    val absoluteUrl: String = ""
) {
    // 详情页"格式"一行要展示的扩展名，例如 "loading-logo.f0c3ebe.png" -> "PNG"。
    // 没有扩展名时回退成空串，让 UI 自己决定是否隐藏该行。
    val extensionText: String
        get() {
            val dotIndex = name.lastIndexOf(EXTENSION_SEPARATOR)
            if (dotIndex <= 0 || dotIndex == name.length - 1) return ""
            return name.substring(dotIndex + 1).uppercase()
        }

    // 详情页"修改"一行只展示日期，时间部分对看图场景没有意义。
    val modifiedDateText: String
        get() = if (modifiedIso.length >= ISO_DATE_PART_LENGTH) {
            modifiedIso.substring(0, ISO_DATE_PART_LENGTH)
        } else {
            modifiedIso
        }
}

/**
 * GET /api/images 的响应（一页）。
 *
 * 到底判据有两个，缺一不可（两个都用实测数据校准过）：
 * - returned < limit：这次没拉满，说明后面没有了；
 * - offset + returned >= total：游标已经追上总数。
 * 只用前一个会漏判"越界请求"——实测 offset=999999 时后端回 returned=0、total 仍是真实值，
 * 此时 returned < limit 成立所以能正确判到底；但若把"等于"当"还有"就会多请求一次空页。
 */
data class PagedImages(
    val path: String = "",
    val root: String = "",
    val recursive: Boolean = true,
    val count: Int = 0,
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = DEFAULT_PAGE_SIZE,
    val returned: Int = 0,
    val images: List<ImageItem> = emptyList()
) {
    // 是否已经拉到底：UI 据此决定"加载更多"要不要继续触发。
    val isEnd: Boolean
        get() = returned < limit || offset + returned >= total

    // 下一页从哪开始。到底之后这个值无意义，调用方应先看 isEnd。
    val nextOffset: Int
        get() = offset + returned

    // 是否还有下一页。与 isEnd 互为反面，单独暴露是为了让 UI 判断条件读起来是正向的。
    val hasMore: Boolean
        get() = !isEnd
}

/**
 * GET /api/status 的响应。
 *
 * needsAuth 是网页版用来决定要不要弹登录框的开关，读接口本身免鉴权，
 * 因此原生端现阶段只把它当展示信息，不做登录流程。
 */
data class ServerStatus(
    val ok: Boolean = false,
    val needsSetup: Boolean = false,
    val needsAuth: Boolean = false,
    val authed: Boolean = false,
    val port: Int = 0,
    val uptimeSec: Long = 0L,
    val version: String = ""
)
