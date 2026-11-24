// 收藏子状态机：收藏列表、游客上传列表、"当前这张图收没收藏"、增删中状态、失败提示。
//
// 它是第 6 个子状态机，和浏览/下载/登录/设置/侧栏同级：只通过 StateChannel 读写状态，
// 跨功能的动作全部靠构造时注入的回调，不持有任何兄弟状态机：
//   - [repositoryProvider]：拿"现在那台服务器"的仓库（换地址会整体替换，所以给 provider 不给实例）；
//   - [tokenProvider]：写接口要 Bearer token，token 归登录子状态机管，这里只"取用"不"保管"；
//   - [onRequestLogin]：未登录时点收藏 → 直接借用登录弹层的入口，**不在这里重新实现登录判断**。
//
// src 一律走 MediaSrc.normalize：收藏的比对与去重全靠它，混用原始写法会让同一张图被当成两张。

package ui.screens.state

import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.FavoriteEntry
import com.eightfs.lumigather.data.FavoriteRequest
import com.eightfs.lumigather.data.LumigatherRepository
import com.eightfs.lumigather.data.MediaSrc
import com.eightfs.lumigather.data.UploadedEntry
import com.eightfs.lumigather.data.toImageItem

class FavoriteController(
    private val channel: StateChannel,
    private val repositoryProvider: () -> LumigatherRepository?,
    private val tokenProvider: () -> String,
    private val onRequestLogin: () -> Unit,
) {

    /** 拉收藏列表（游客也能看）。失败给中文提示，不静默。 */
    suspend fun loadFavorites() {
        val activeRepository = repositoryProvider() ?: return
        channel.mutate { it.copy(favoritesLoading = true) }
        when (val result = activeRepository.loadFavorites()) {
            is ApiResult.Ok -> applyFavorites(result.value, activeRepository)
            is ApiResult.Err -> channel.mutate {
                it.copy(
                    favoritesLoading = false,
                    actionHint = describeFailure(LOAD_FAVORITES_FAILED, result.error),
                )
            }
        }
    }

    /** 拉游客上传列表（游客也能看）。 */
    suspend fun loadGuestUploads() {
        val activeRepository = repositoryProvider() ?: return
        channel.mutate { it.copy(guestLoading = true) }
        when (val result = activeRepository.loadUploaded()) {
            is ApiResult.Ok -> applyGuest(result.value, activeRepository)
            is ApiResult.Err -> channel.mutate {
                it.copy(
                    guestLoading = false,
                    actionHint = describeFailure(LOAD_GUEST_FAILED, result.error),
                )
            }
        }
    }

    /**
     * 收藏 / 取消收藏当前详情图。
     *
     * 三个前置判断的顺序就是用户能感知到的行为：
     * 1) 没有选中图 → 什么都不做；
     * 2) 未登录 → 弹登录（登录成功后用户再点一次即可，不做"登录后自动补收藏"这种隐式动作）；
     * 3) 已有一次增删在飞 → 直接返回（防连点，服务端没有幂等保护）。
     */
    suspend fun toggleFavorite() {
        val current = channel.state
        val image = current.selectedImage ?: return
        if (!current.auth.loggedIn) {
            onRequestLogin()
            return
        }
        if (current.favoriteActionSrc.isNotEmpty()) return

        val activeRepository = repositoryProvider() ?: return
        val src = MediaSrc.normalize(image.relativeUrl)
        if (src.isEmpty()) {
            channel.mutate { it.copy(actionHint = NO_MEDIA_SRC) }
            return
        }

        channel.mutate { it.copy(favoriteActionSrc = src) }
        val token = tokenProvider()
        if (src in current.favoriteSrcs) {
            remove(activeRepository, src, token)
        } else {
            add(activeRepository, image, src, current.selectedCategoryView?.label.orEmpty(),
                current.selectedCategoryView?.rootPath.orEmpty(), token)
        }
    }

    private suspend fun add(
        activeRepository: LumigatherRepository,
        image: com.eightfs.lumigather.data.ImageItem,
        src: String,
        categoryLabel: String,
        categoryRoot: String,
        token: String,
    ) {
        val request = FavoriteRequest(
            src = src,
            name = image.name,
            title = image.name,
            cat = categoryLabel,
            dir = image.path.substringBeforeLast('/'),
            size = image.sizeText,
            root = categoryRoot,
        )
        when (val result = activeRepository.addFavorite(request, token)) {
            is ApiResult.Ok -> {
                applyFavorites(result.value.favorites, activeRepository)
                channel.mutate {
                    it.copy(
                        favoriteActionSrc = "",
                        actionHint = if (result.value.alreadyExists) ALREADY_FAVORITED else FAVORITED,
                    )
                }
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(favoriteActionSrc = "", actionHint = describeFailure(ADD_FAILED, result.error))
            }
        }
    }

    private suspend fun remove(activeRepository: LumigatherRepository, src: String, token: String) {
        when (val result = activeRepository.removeFavorite(src, token)) {
            is ApiResult.Ok -> {
                applyFavorites(result.value, activeRepository)
                channel.mutate { it.copy(favoriteActionSrc = "", actionHint = UNFAVORITED) }
            }

            is ApiResult.Err -> channel.mutate {
                it.copy(favoriteActionSrc = "", actionHint = describeFailure(REMOVE_FAILED, result.error))
            }
        }
    }

    /**
     * 用服务端回传的列表整体替换本地收藏。
     *
     * 不做"本地增删一条"的增量更新：服务端在 alreadyExists / 并发删除等情况下回的是权威列表，
     * 以它为准才不会出现"界面已收藏、服务端没有"的漂移。
     */
    private fun applyFavorites(entries: List<FavoriteEntry>, activeRepository: LumigatherRepository) {
        // 按规范化 src 去重：旧数据里同一张图可能同时存在"完整 URL"和"相对路径"两条记录，
        // 不去重的话网格里会出现两张一模一样的图、计数也会虚高。
        val uniqueEntries = entries.distinctBy { it.src }
        val images = uniqueEntries.map { entry ->
            entry.toImageItem(activeRepository.resolveMediaUrl(entry.src))
        }
        channel.mutate {
            it.copy(
                favoriteImages = images,
                favoriteSrcs = uniqueEntries.map { it.src }.toSet(),
                favoritesLoading = false,
            )
        }
    }

    /**
     * 清空两套列表（换服务器时由 facade 调用）。
     *
     * 不清的话，用户换了服务器还会看到上一台的收藏与游客上传 —— 那些地址在新服务器上根本不存在，
     * 点开会 404，而界面上看不出任何异常。
     */
    fun resetLists() {
        channel.mutate {
            it.copy(
                favoriteImages = emptyList(),
                favoriteSrcs = emptySet(),
                guestImages = emptyList(),
                favoritesLoading = false,
                guestLoading = false,
                favoriteActionSrc = "",
            )
        }
    }

    private fun applyGuest(entries: List<UploadedEntry>, activeRepository: LumigatherRepository) {
        val images = entries.map { entry ->
            // 上传项的 url 与 src 都是相对地址（/uploads/xxx）；优先用 url（服务端明确给的可加载地址）
            entry.toImageItem(activeRepository.resolveMediaUrl(entry.url.ifBlank { entry.src }))
        }
        channel.mutate { it.copy(guestImages = images, guestLoading = false) }
    }

    // 失败提示统一成「做什么 + 为什么」：只说"失败"用户没法判断该重试还是该去登录
    private fun describeFailure(action: String, error: ApiError): String = "$action：${error.userMessage}"
}

private const val LOAD_FAVORITES_FAILED = "加载收藏失败"
private const val LOAD_GUEST_FAILED = "加载游客上传失败"
private const val ADD_FAILED = "收藏失败"
private const val REMOVE_FAILED = "取消收藏失败"
private const val FAVORITED = "已收藏"
private const val UNFAVORITED = "已取消收藏"
private const val ALREADY_FAVORITED = "已经在收藏里了"
private const val NO_MEDIA_SRC = "这张图没有可收藏的地址"
