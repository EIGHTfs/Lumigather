// 会话 token 的本地持久化声明。
//
// 与服务器地址用同一套 expect/actual 模式、同一个 SharedPreferences 文件：
// 两者都是"本机设置"，分文件只会在清理/迁移时漏掉一处。
//
// 读取的容错要求（不是可选项）：token 为空、到期时间缺失或格式异常，一律当作"未登录"返回 null，
// 并且顺手把脏数据清掉 —— 否则每次启动都会带着一条读不出来的坏记录，用户还会以为"我明明登录过"。

package com.eightfs.lumigather.data

/**
 * 本地保存的会话。
 *
 * @param expiresAtEpochMs 客户端按服务端 TTL 推算的到期时刻（毫秒时间戳）
 */
data class StoredAuthToken(
    val token: String,
    val expiresAtEpochMs: Long,
) {
    // 到期即视为无效：过期的 token 发给服务端只会换来 401，不如本地就拦住
    fun isExpired(nowEpochMs: Long): Boolean = expiresAtEpochMs <= nowEpochMs

    // 同样覆盖 toString：避免有人把整个对象打进日志
    override fun toString(): String = "StoredAuthToken(expiresAtEpochMs=$expiresAtEpochMs)"
}

interface AuthTokenStore {
    /** 读出可用的会话；没有、读失败或格式异常都返回 null。 */
    fun read(): StoredAuthToken?

    fun write(token: String, expiresAtEpochMs: Long)

    fun clear()
}

// 平台实现由 androidMain 提供（SharedPreferences）。
expect fun createAuthTokenStore(): AuthTokenStore

/**
 * 当前时间（毫秒时间戳）。
 *
 * 用 expect 而不是在 commonMain 里直接取时间，是因为 commonMain 没有可用的时钟 API；
 * 同时把它做成可注入的（状态层收 `nowProvider`），测试才能稳定地构造"已过期"这种场景。
 */
expect fun currentEpochMillis(): Long
