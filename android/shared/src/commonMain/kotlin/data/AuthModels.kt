// 登录相关的数据模型与会话有效期常量。
//
// 有效期为什么由客户端推算：`POST /api/login` 的响应只有 token 与 cookieName，不返回到期时间。
// 服务端签会话时按「记住此设备 → 720h，否则 72h」算，客户端必须用同一套数字推算本地到期时刻，
// 两边不一致的后果很难查 —— 本地以为还有效、服务端已经拒绝，表现就是"莫名其妙 401"。
//
// token 的处置原则：只在内存与 SharedPreferences 里出现，不进日志、不进异常信息、不进崩溃栈。

package com.eightfs.lumigather.data

// 勾选「记住此设备」时的会话时长（30 天）。与服务端 sessionRememberHours 默认值一致。
const val REMEMBER_SESSION_HOURS = 720

// 未勾选时的会话时长（3 天）。与服务端 config.sessionHours 默认值一致。
const val DEFAULT_SESSION_HOURS = 72

private const val MILLIS_PER_HOUR = 3_600_000L

/**
 * 一次成功登录拿到的会话信息。
 *
 * @param noPassword 服务端还没有设置访问密码（此时登录直接放行，需要提示用户尽快设置）
 * @param remember    本次是否勾选了「记住此设备」，决定本地推算的有效期
 */
data class LoginSession(
    val token: String,
    val cookieName: String = "",
    val noPassword: Boolean = false,
    val message: String = "",
    val remember: Boolean = false,
) {
    // 本地推算的会话时长
    val lifetimeHours: Int
        get() = if (remember) REMEMBER_SESSION_HOURS else DEFAULT_SESSION_HOURS

    // 本地推算的到期时刻（毫秒时间戳）
    fun expiresAt(nowEpochMs: Long): Long = nowEpochMs + lifetimeHours * MILLIS_PER_HOUR

    /**
     * 刻意覆盖 toString：data class 默认会把所有字段拼进字符串，一旦有人写下
     * `Log.d(TAG, "$session")` 或把对象塞进异常信息，token 就跟着进了日志。
     * 这里只保留"是不是无密码登录""记没记住"两个非敏感事实。
     */
    override fun toString(): String = "LoginSession(noPassword=$noPassword, remember=$remember)"
}
