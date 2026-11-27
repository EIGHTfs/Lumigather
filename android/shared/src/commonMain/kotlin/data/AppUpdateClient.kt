// 从 GitHub Releases 查最新发布（APK 自动更新的"检查更新"一步）。
//
// 为什么打 GitHub 而不是自家服务端：Lumigather 服务端仓库是 public 的，8MB 的 APK 不该进 git；
// 而手机只要能上网就能访问 api.github.com（实测 200），比"服务端得先存一份 APK"更省事。
//
// 与 ApiClient 的区别：ApiClient 绑着一个 Lumigather 服务的 baseUrl，这里访问的是外部域，
// 所以单独一个 client —— 但它复用同一个 HttpTransport 抽象，超时/异常语义保持一致。

package com.eightfs.lumigather.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/** GitHub 仓库坐标：owner/repo 与 API 路径都在这里，改仓库只改一处。 */
private const val GITHUB_OWNER = "EIGHTfs"
private const val GITHUB_REPO = "Lumigather"
private const val DEFAULT_RELEASES_URL =
    "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

// APK 资产的判定：GitHub 上 asset 名由发布者起，靠扩展名认出安装包
private const val APK_EXTENSION = ".apk"

// GitHub 的 digest 形如 "sha256:99453c…"；不带前缀的写法也容忍
private const val DIGEST_SHA256_PREFIX = "sha256:"

// 发布说明太长会挤满整个页面，超出部分截断（完整说明在 Release 页面可看）
private const val RELEASE_NOTES_MAX_CHARS = 600

class AppUpdateClient(
    private val transport: HttpTransport,
    private val releasesUrl: String = DEFAULT_RELEASES_URL,
    private val timeoutMs: Long = RELEASE_TIMEOUT_MS,
) {

    /**
     * 查最新发布。
     *
     * 404 是正常情况（仓库还没发过 Release），映射成中文提示而不是"网络错误" ——
     * 否则用户会以为是自己网络的问题，反复重试。
     */
    suspend fun fetchLatestRelease(): ApiResult<RemoteRelease> {
        val raw = try {
            transport.get(releasesUrl, emptyMap(), timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Network(describeFailure(failure), failure))
        }

        if (raw.statusCode == HTTP_NOT_FOUND) {
            return ApiResult.Err(ApiError.Http(HTTP_NOT_FOUND, NO_RELEASE_MESSAGE))
        }
        if (raw.statusCode == HTTP_FORBIDDEN || raw.statusCode == HTTP_TOO_MANY_REQUESTS) {
            return ApiResult.Err(ApiError.Http(raw.statusCode, RATE_LIMITED_MESSAGE))
        }
        if (raw.statusCode !in HTTP_SUCCESS_RANGE_FIRST..HTTP_SUCCESS_RANGE_LAST) {
            return ApiResult.Err(ApiError.Http(raw.statusCode, ""))
        }

        val release = try {
            parseRelease(JSONObject(raw.bodyText))
        } catch (failure: Throwable) {
            return ApiResult.Err(ApiError.Parse(describeFailure(failure), raw.bodyText.take(200)))
        }
        return ApiResult.Ok(release)
    }
}

/**
 * Release JSON → 模型。
 *
 * 只取第一个 `.apk` 资产：本项目不按 ABI/渠道分发（单包），多资产时按发布顺序取首个安装包。
 * 一个 apk 都没有时按"没有可分发的包"报错 —— 这比返回一个下载地址为空的模型更好排查。
 */
internal fun parseRelease(json: JSONObject): RemoteRelease {
    val tagName = json.optString(RELEASE_FIELD_TAG, "")
    val assets = json.optJSONArray(RELEASE_FIELD_ASSETS)
    val apkAsset = findApkAsset(assets)
        ?: throw IllegalStateException(NO_APK_IN_RELEASE)

    return RemoteRelease(
        versionName = tagName,
        versionCode = VersionTag.parseVersionCode(tagName),
        sizeBytes = apkAsset.optLong(ASSET_FIELD_SIZE, 0L),
        downloadUrl = apkAsset.optString(ASSET_FIELD_DOWNLOAD_URL, ""),
        sha256 = normalizeDigest(apkAsset.optString(ASSET_FIELD_DIGEST, "")),
        releaseNotes = json.optString(RELEASE_FIELD_BODY, "").trim().take(RELEASE_NOTES_MAX_CHARS),
        publishedAt = json.optString(RELEASE_FIELD_PUBLISHED_AT, ""),
    )
}

// 在 assets 里找第一个 apk（大小写不敏感：发布者可能传 .APK）
private fun findApkAsset(assets: JSONArray?): JSONObject? {
    if (assets == null) return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        val name = asset.optString(ASSET_FIELD_NAME, "")
        if (name.endsWith(APK_EXTENSION, ignoreCase = true)) return asset
    }
    return null
}

// digest → 纯 hex。空串（GitHub 没给）返回 null：调用方据此决定"跳过校验"而不是"校验空值通过"
private fun normalizeDigest(digest: String): String? {
    val trimmed = digest.trim()
    if (trimmed.isEmpty()) return null
    val hex = trimmed.removePrefix(DIGEST_SHA256_PREFIX).trim()
    return hex.ifEmpty { null }
}

private const val RELEASE_FIELD_TAG = "tag_name"
private const val RELEASE_FIELD_ASSETS = "assets"
private const val RELEASE_FIELD_BODY = "body"
private const val RELEASE_FIELD_PUBLISHED_AT = "published_at"
private const val ASSET_FIELD_NAME = "name"
private const val ASSET_FIELD_SIZE = "size"
private const val ASSET_FIELD_DOWNLOAD_URL = "browser_download_url"
private const val ASSET_FIELD_DIGEST = "digest"

private const val HTTP_SUCCESS_RANGE_FIRST = 200
private const val HTTP_SUCCESS_RANGE_LAST = 299
private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429

// 查版本不该让用户等太久：GitHub 在国内可能慢，10 秒没响应就报超时
private const val RELEASE_TIMEOUT_MS = 10_000L

private const val NO_RELEASE_MESSAGE = "仓库还没有发布版本"
private const val RATE_LIMITED_MESSAGE = "GitHub 访问过于频繁，请稍后再试"
private const val NO_APK_IN_RELEASE = "最新发布里没有 APK 安装包"
