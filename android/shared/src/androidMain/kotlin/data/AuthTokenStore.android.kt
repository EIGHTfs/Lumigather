// 会话 token 持久化的 Android 实现（SharedPreferences）。
//
// 键名收成常量：它们一旦定下来就有"旧安装升级后还读得到"的兼容含义，
// 散落成字面量最容易在重构时被顺手改掉，导致老用户登录态凭空消失。
//
// 用 commit 而不是 apply：登录/退出都是用户点一次的确定性动作，主线程阻塞一两毫秒无所谓，
// 换来的是"点完立刻杀进程，登录态还在/已经清掉"这个确定性 —— 登录态丢一半比卡两毫秒难受得多。

package com.eightfs.lumigather.data

import android.content.Context
import android.content.SharedPreferences
import ui.screens.settings.AndroidHostContext
import ui.screens.settings.SETTINGS_PREFERENCES_NAME

// 与服务器地址同文件（见 SETTINGS_PREFERENCES_NAME 的说明），键分开
private const val KEY_AUTH_TOKEN = "auth_token"
private const val KEY_AUTH_EXPIRES_AT_MS = "auth_expires_at_epoch_ms"

private class SharedPreferencesAuthTokenStore(
    private val context: Context,
) : AuthTokenStore {

    private val preferences: SharedPreferences
        get() = context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): StoredAuthToken? {
        val prefs = preferences
        val token = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty()
        val expiresAt = prefs.getLong(KEY_AUTH_EXPIRES_AT_MS, 0L)

        // 脏数据（空 token / 缺到期时间）当未登录，并顺手清掉 —— 留着只会每次启动都读一遍坏值
        if (token.isBlank() || expiresAt <= 0L) {
            if (!token.isBlank() || expiresAt != 0L) clear()
            return null
        }
        return StoredAuthToken(token = token, expiresAtEpochMs = expiresAt)
    }

    override fun write(token: String, expiresAtEpochMs: Long) {
        preferences.edit()
            .putString(KEY_AUTH_TOKEN, token)
            .putLong(KEY_AUTH_EXPIRES_AT_MS, expiresAtEpochMs)
            .commit()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_AUTH_EXPIRES_AT_MS)
            .commit()
    }
}

// 宿主没装上下文时的兜底：值只活在内存里，重启即忘（表现为"又要登录一次"）。
// 它存在只是为了不崩，正常路径永远走 SharedPreferences。
private object VolatileAuthTokenStore : AuthTokenStore {
    private var stored: StoredAuthToken? = null

    override fun read(): StoredAuthToken? = stored

    override fun write(token: String, expiresAtEpochMs: Long) {
        stored = StoredAuthToken(token, expiresAtEpochMs)
    }

    override fun clear() {
        stored = null
    }
}

actual fun createAuthTokenStore(): AuthTokenStore {
    val context = AndroidHostContext.current() ?: return VolatileAuthTokenStore
    return SharedPreferencesAuthTokenStore(context)
}

// 系统墙上时间。用墙钟而不是 elapsedRealtime：会话到期是"绝对时刻"，
// 用户改时间/跨时区时墙钟才符合服务端对 exp 的判断口径。
actual fun currentEpochMillis(): Long = System.currentTimeMillis()
