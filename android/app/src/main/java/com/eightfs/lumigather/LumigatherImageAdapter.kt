// 图片加载适配器：把 Kuikly 的图片请求接到本机可用的加载实现上。
//
// 为什么必须有它：Kuikly 的 Image / coil3 painter 最终都落到渲染层的 KRImageView，
// 而 KRImageView 只通过 KuiklyRenderAdapterManager.krImageAdapter 去取图。
// 没注册适配器时编译、打包、启动全都正常，但真机上**所有图永远停在占位态**——
// 这是最容易误判成"网络不通"的静默失败，所以注册这一步写在 MainActivity 里且不吞异常。
//
// 为什么自己实现而不引入 Glide：官方 demo 用 Glide 是为演示图片变换能力；
// 本项目只需要"把 URL 变成 Bitmap"，用系统自带的 HttpURLConnection + BitmapFactory 就够，
// 少一个第三方库、少一套注解处理器，也不与 Kuikly 的依赖树互相牵制。

package com.eightfs.lumigather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// 下载并发数：网格一屏 9~12 格，4 个并发既能让首屏较快铺满，又不会把 NAS 的带宽与磁盘压满。
private const val DOWNLOAD_THREAD_COUNT = 4

// 连接/读超时：局域网内正常 1 秒内连通；大图几 MB，读取给到 20 秒。
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

// 内存缓存占可用堆的比例。图片网格最容易吃内存，1/8 是 Android 官方推荐量级。
private const val CACHE_HEAP_DIVISOR = 8

// 缓存下限，避免小内存设备上算出接近 0 的容量。
private const val MIN_CACHE_BYTES = 8 * 1024 * 1024

// 请求宽高为 0 表示 Kuikly 没量出目标尺寸，此时按原图解码、不降采样。
private const val NO_SIZE_HINT = 0

// 降采样只按 2 的幂递增，这是 BitmapFactory.inSampleSize 的硬性要求。
private const val SAMPLE_SIZE_STEP = 2
private const val SAMPLE_SIZE_MIN = 1

private const val SCHEME_FILE_PREFIX = "file://"
private const val BASE64_PAYLOAD_SEPARATOR = ","
private val HTTP_OK_RANGE = 200..299

/**
 * 基于系统 HTTP 与 BitmapFactory 的图片适配器。
 *
 * 线程模型：Kuikly 可能在任意线程调 [fetchDrawable]（接口注释里明确说明了），所以取图丢到固定线程池，
 * 结果用主线程 Handler 回投 —— 回调里会写 View，必须在主线程。
 */
class LumigatherImageAdapter(
    private val context: Context,
) : IKRImageAdapter {

    // 键是「地址 + 目标尺寸」：同一张图在缩略图与大图两种尺寸下是不同的位图，不能共用键。
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = Executors.newFixedThreadPool(DOWNLOAD_THREAD_COUNT)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 旧接口（Kuikly 已标废弃，但部分内部路径仍会调用），委托到同一实现，
    // 避免新旧两条路径行为不一致（比如一条带缓存一条不带）。
    @Deprecated("Kuikly 已改用带 imageParams 的重载，这里保留以兼容旧调用路径")
    override fun fetchDrawable(imageLoadOption: HRImageLoadOption, callback: (drawable: Drawable?) -> Unit) {
        fetchDrawable(imageLoadOption, null, callback)
    }

    override fun fetchDrawable(
        imageLoadOption: HRImageLoadOption,
        imageParams: JSONObject?,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        executor.execute {
            // 取图失败不能抛：一格的失败只该让那一格显示占位，不能让整个网格崩掉。
            val drawable = runCatching { loadDrawable(imageLoadOption) }.getOrNull()
            mainHandler.post { callback(drawable) }
        }
    }

    // 位图是按像素解出来的，不再乘 density —— 乘了会让布局按错误尺寸排版（图片被放大）。
    override fun getDrawableWidth(kuiklyRenderViewContext: KuiklyRenderViewContext, drawable: Drawable): Float =
        drawable.intrinsicWidth.toFloat()

    override fun getDrawableHeight(kuiklyRenderViewContext: KuiklyRenderViewContext, drawable: Drawable): Float =
        drawable.intrinsicHeight.toFloat()

    private fun loadDrawable(option: HRImageLoadOption): Drawable? {
        if (option.src.isEmpty()) return null

        val cacheKey = buildCacheKey(option)
        memoryCache.get(cacheKey)?.let { cached -> return BitmapDrawable(context.resources, cached) }

        // 三个来源共用一个解码入口，目标尺寸从 option 透传下去（而不是藏在字段里，
        // 那样多线程并发时会互相覆盖，采样率算错）。
        val bitmap = when {
            option.isBase64() -> decodeBase64(option.src)
            option.isAssets() -> decodeAssets(option.src, option)
            option.isFile() -> decodeFile(option)
            option.isWebUrl() -> decodeRemote(option)
            else -> null
        } ?: return null

        memoryCache.put(cacheKey, bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    // 键里带目标尺寸：同一 URL 的缩略图与大图各自缓存，避免大图被缩略图尺寸的位图顶替。
    private fun buildCacheKey(option: HRImageLoadOption): String =
        "${option.src}#${option.requestWidth}x${option.requestHeight}"

    // base64 图没有尺寸可调，直接解（通常是小图标）。
    private fun decodeBase64(source: String): Bitmap? {
        val payload = source.substringAfter(BASE64_PAYLOAD_SEPARATOR, source)
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun decodeAssets(source: String, option: HRImageLoadOption): Bitmap? {
        val assetPath = source.removePrefix(HRImageLoadOption.SCHEME_ASSETS)
        val bytes = runCatching { context.assets.open(assetPath).use { stream -> stream.readBytes() } }
            .getOrNull() ?: return null
        return decodeBytes(bytes, option)
    }

    private fun decodeFile(option: HRImageLoadOption): Bitmap? {
        val path = Uri.parse(option.src).path ?: option.src.removePrefix(SCHEME_FILE_PREFIX)
        if (!java.io.File(path).exists()) return null
        return decodeWithSampling(option) { options -> BitmapFactory.decodeFile(path, options) }
    }

    // 网络图：先读字节，再按目标尺寸降采样解码。
    // 直接 decodeStream 会把大图按原尺寸展开，NAS 上的图常见几 MB、几千像素宽，
    // 网格里同时解 9 张很容易 OOM。
    private fun decodeRemote(option: HRImageLoadOption): Bitmap? {
        val bytes = downloadBytes(option.src) ?: return null
        return decodeBytes(bytes, option)
    }

    private fun decodeBytes(bytes: ByteArray, option: HRImageLoadOption): Bitmap? =
        decodeWithSampling(option) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }

    private fun downloadBytes(url: String): ByteArray? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in HTTP_OK_RANGE) return null
            connection.inputStream.use { stream -> stream.readBytes() }
        } catch (failure: Exception) {
            // 单张图失败是常态（文件被删、临时断网），返回 null 让格子显示占位即可
            null
        } finally {
            connection.disconnect()
        }
    }

    // 两段式解码：先只读尺寸算出 inSampleSize，再真正解码。
    // 这样内存峰值是"目标尺寸"而不是"原图尺寸"。
    private fun decodeWithSampling(
        option: HRImageLoadOption,
        decoder: (BitmapFactory.Options) -> Bitmap?,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decoder(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateSampleSize(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetWidth = option.requestWidth,
            targetHeight = option.requestHeight,
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // RGB_565：网格缩略图不需要 alpha，能省一半内存；详情大图另走一次请求（尺寸更大）
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return decoder(decodeOptions)
    }

    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (targetWidth <= NO_SIZE_HINT || targetHeight <= NO_SIZE_HINT) return SAMPLE_SIZE_MIN
        var sampleSize = SAMPLE_SIZE_MIN
        while (sourceWidth / (sampleSize * SAMPLE_SIZE_STEP) >= targetWidth &&
            sourceHeight / (sampleSize * SAMPLE_SIZE_STEP) >= targetHeight
        ) {
            sampleSize *= SAMPLE_SIZE_STEP
        }
        return sampleSize
    }

    companion object {
        // 缓存上限取可用堆的 1/8，但不低于下限，避免小内存设备上容量趋近 0。
        // maxMemory() 是 Long，LruCache 要 Int：先整除再取整，1/8 的截断误差可以忽略。
        private fun cacheSizeBytes(): Int {
            val oneEighth = (Runtime.getRuntime().maxMemory() / CACHE_HEAP_DIVISOR).toInt()
            return oneEighth.coerceAtLeast(MIN_CACHE_BYTES)
        }
    }
}
