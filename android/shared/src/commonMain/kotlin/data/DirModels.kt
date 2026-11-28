// `/api/dir` 的响应模型（按需加载单层目录）。
//
// 为什么不用 `/api/directories`：那个接口只给子目录名，既拿不到张数，也判断不了"空目录"，
// 客户端就得自己再猜一次 —— 而 `/api/dir` 一次性把「本层图片 + 子目录 + 每个子目录的统计」都给全了。
//
// count 有三个语义（最容易搞错的一处，实测确认）：
//   count > 0  → 已知张数（递归统计）
//   count == 0 → 确定没有图
//   count < 0  → **未知**（后端递归缓存未覆盖时，单层扫描回落会把 count 置 -1，同时假定 hasSub=true）
// 把 -1 当成 0 会把有图目录误判成空目录 —— 实测 `Game.Patch N MOD` 下 65 个子目录全是 -1。

package com.eightfs.lumigather.data

/**
 * 一层目录的内容。
 *
 * @param path 相对分类根目录的路径（""=根；"Mods"；"Mods/.Mods"），用于面包屑与回退
 * @param images 本层直接包含的图片（与 `/api/images` 同结构）
 * @param subdirs 子目录及其统计
 */
data class DirPage(
    val root: String = "",
    val path: String = "",
    val subdirs: List<DirSubdir> = emptyList(),
    val images: List<ImageItem> = emptyList(),
    val sig: Double = 0.0,
)

/**
 * 一个子目录。
 *
 * @param relPath 相对分类根的路径（父目录的 path + "/" + name）
 * @param count 图片张数；**负数表示未知**，不是"零张"
 * @param hasSub 是否有子目录。hasSub=false 时后端会顺带给 [leafImages]
 * @param leafImages 叶子目录里直接包含的图片（hasSub=true 时为空）——
 *   这些图要直接摊进网格，而不是让用户再点一次进入
 */
data class DirSubdir(
    val name: String = "",
    val relPath: String = "",
    val count: Int = 0,
    val hasSub: Boolean = false,
    val mtime: Long = 0L,
    val leafImages: List<ImageItem> = emptyList(),
)
