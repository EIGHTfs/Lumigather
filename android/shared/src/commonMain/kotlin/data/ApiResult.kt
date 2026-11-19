// 数据层的错误与结果表达。
//
// 核心约定：**任何失败都变成值，不往上抛异常**。UI 层拿到的一定是 Ok 或 Err，
// 于是"网络断了 / 服务器 500 / JSON 变了"三种情况都只需在页面上画一行错误 + 重试按钮，
// 不会因为一个未捕获异常把整个页面炸掉。
//
// 每个错误分支都自带 userMessage：这句话是可以直接上屏的中文。技术细节（状态码、原始 body）
// 留在结构化字段里给日志用，不直接显示给用户 —— 用户看不懂 ENOENT。

package com.eightfs.lumigather.data

/**
 * 失败分类。五个分支对应五种可观测现象，UI 与日志据此分流：
 * - [Network] 连不上（超时、DNS、连接被拒）—— 可重试，通常是地址填错或没网；
 * - [Http] 连上了但状态码不对，或服务端在 200 里塞了 error 字段 —— 可重试，但要报状态码；
 * - [Parse] 连上了、状态码也对，但返回的不是预期的 JSON —— 重试大概率没用，属于契约漂移；
 * - [InvalidConfig] 服务器地址本身不可用（空串）—— 应该引导用户去设置页，而不是让他狂点重试；
 * - [BadArgument] 调用方参数有问题（如 root 为空）—— 程序问题，本地就拦掉，别浪费一次请求。
 */
sealed class ApiError {
    // 可直接展示给用户的中文说明。
    abstract val userMessage: String

    // 原始异常，只给日志/排查用，不要塞进 UI 文案。
    open val cause: Throwable? = null

    class Network(val detail: String, override val cause: Throwable? = null) : ApiError() {
        override val userMessage: String = "无法连接服务器，请检查网络与服务器地址"
    }

    class Http(val statusCode: Int, val serverMessage: String) : ApiError() {
        override val userMessage: String = "服务器返回错误（$statusCode）"
    }

    class Parse(val detail: String, val bodySnippet: String) : ApiError() {
        override val userMessage: String = "服务器返回的数据无法解析"
    }

    class InvalidConfig(val detail: String) : ApiError() {
        override val userMessage: String = "服务器地址无效，请先在设置里填写"
    }

    class BadArgument(val detail: String) : ApiError() {
        override val userMessage: String = "请求参数缺失"
    }
}

/**
 * 结果包装。协变（out T）是为了让 [Err] 能作为任意 T 的结果返回 —— 失败与数据类型无关。
 */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: ApiError) : ApiResult<Nothing>()
}

// 三个使用频次最高的取值助手。写在顶层而不是成员，是为了让调用方写
// `result.getOrNull()` 这种链式风格时不必先解构。
fun <T> ApiResult<T>.getOrNull(): T? = when (this) {
    is ApiResult.Ok -> value
    is ApiResult.Err -> null
}

fun <T> ApiResult<T>.errorOrNull(): ApiError? = when (this) {
    is ApiResult.Err -> error
    is ApiResult.Ok -> null
}

val ApiResult<*>.isOk: Boolean
    get() = this is ApiResult.Ok

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    when (this) {
        is ApiResult.Ok -> block(value)
        is ApiResult.Err -> Unit
    }
    return this
}

inline fun <T> ApiResult<T>.onErr(block: (ApiError) -> Unit): ApiResult<T> {
    when (this) {
        is ApiResult.Err -> block(error)
        is ApiResult.Ok -> Unit
    }
    return this
}

// 把任意异常压成一行可读文本：优先取 message（OkHttp/系统网络栈的 message 最有信息量），
// 没有 message 时退回 toString（至少能看出异常类型）。
internal fun describeFailure(failure: Throwable): String {
    val message = failure.message
    return if (message.isNullOrBlank()) failure.toString() else message
}
