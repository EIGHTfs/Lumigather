// 登录子状态机：登录态、token 恢复、失败冷却、401 清理、退出登录。
//
// 这是拆分里最绕的一块，因为它同时碰"本地存储（token）"与"远端（登录/登出）"，
// 还背着一条来自数据层的异步回调（401）。依赖依然只有三样：
//   - 状态通道（读写登录相关字段）；
//   - token 存储与时钟（本地事实）；
//   - 一个"现在那个仓库"的 provider（远端调用），而不是持有仓库本身 ——
//     仓库是会被换地址整体替换的，持有它就等于把登录绑死在某一台服务器上。
//
// 401 链路：ApiClient 在非登录请求收到 401 时回调 facade 的方法 → facade 转给这里的
// [handleUnauthorized]。链路必须一直在，否则会话过期后界面只会显示"加载失败"。

package ui.screens.state

import com.eightfs.lumigather.data.ApiError
import com.eightfs.lumigather.data.ApiResult
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.DEFAULT_SESSION_HOURS
import com.eightfs.lumigather.data.LumigatherRepository
import kotlinx.coroutines.delay

class AuthController(
    private val channel: StateChannel,
    private val authTokenStore: AuthTokenStore,
    private val nowProvider: () -> Long,
    private val repositoryProvider: () -> LumigatherRepository?,
) {

    /** 打开登录弹层：清空上次的密码与错误，避免把敏感内容留在界面上。 */
    fun openLoginSheet() {
        channel.mutate {
            it.copy(
                loginSheetOpen = true,
                loginPassword = "",
                loginState = LoginUiState.Idle,
                loginBlocked = false,
                loginRemember = true,
                actionHint = null,
            )
        }
    }

    fun closeLoginSheet() {
        channel.mutate {
            it.copy(
                loginSheetOpen = false,
                loginPassword = "",
                loginState = LoginUiState.Idle,
                loginBlocked = false,
            )
        }
    }

    fun updateLoginPassword(password: String) {
        // 改密码时顺手清掉错误提示：用户已经在改了，还挂着「密码错误」会让人以为没生效
        channel.mutate { it.copy(loginPassword = password, loginState = LoginUiState.Idle) }
    }

    fun toggleLoginRemember(remember: Boolean) {
        channel.mutate { it.copy(loginRemember = remember) }
    }

    /**
     * 提交登录。
     *
     * 失败的处置有两件事：内联显示后端原文（如「密码错误」）+ 冷却 1.5 秒。
     * 冷却放在这里而不是 UI：按钮只是表现，真正的闸门在状态层 —— 否则任何直接调用都能绕过。
     */
    suspend fun submitLogin() {
        val current = channel.state
        if (!current.loginSubmittable) return
        val activeRepository = repositoryProvider()
            ?: run {
                channel.mutate { it.copy(loginState = LoginUiState.Failed("请先配置服务器地址")) }
                return
            }

        val password = current.loginPassword
        val remember = current.loginRemember
        channel.mutate { it.copy(loginState = LoginUiState.Submitting) }

        when (val result = activeRepository.login(password, remember)) {
            is ApiResult.Ok -> {
                val session = result.value
                val expiresAt = session.expiresAt(nowProvider())
                authTokenStore.write(session.token, expiresAt)
                channel.mutate {
                    it.copy(
                        loginSheetOpen = false,
                        // 关掉弹层就把密码从内存里清掉，别让它留在状态里
                        loginPassword = "",
                        loginState = LoginUiState.Idle,
                        loginBlocked = false,
                        auth = AuthUiState(
                            loggedIn = true,
                            rememberDevice = remember,
                            expiresAtEpochMs = expiresAt,
                            noPassword = session.noPassword,
                        ),
                        actionHint = if (session.noPassword) {
                            "服务端未设置密码，建议尽快设置"
                        } else {
                            "登录成功"
                        },
                    )
                }
            }

            is ApiResult.Err -> {
                channel.mutate {
                    it.copy(
                        loginState = LoginUiState.Failed(describeLoginFailure(result.error)),
                        loginBlocked = true,
                    )
                }
                // 冷却：后端没有失败限流/锁定，前端必须自己挡住连点穷举
                delay(FAILED_LOGIN_COOLDOWN_MS)
                channel.mutate { it.copy(loginBlocked = false) }
            }
        }
    }

    /** 退出登录：先尽力通知服务端，再无条件清本地 —— 本地退不掉会把用户困在登录态。 */
    suspend fun logout() {
        val token = authTokenStore.read()?.token.orEmpty()
        repositoryProvider()?.logout(token)
        clearSession()
        channel.mutate { it.copy(actionHint = "已退出登录") }
    }

    // 读本地会话：过期/格式异常一律当未登录，并把坏数据清掉（避免每次启动都带着坏值）
    fun restoreSession() {
        val stored = authTokenStore.read()
        if (stored == null) {
            channel.mutate { it.copy(auth = AuthUiState()) }
            return
        }
        if (stored.isExpired(nowProvider())) {
            authTokenStore.clear()
            channel.mutate { it.copy(auth = AuthUiState()) }
            return
        }
        // 记住勾选状态无法从存储反推，用"剩余时间是否超过短会话时长"判断当时勾没勾；
        // 判错只影响摘要文案（30 天/3 天），不影响会话有效性。
        val remainingHours = (stored.expiresAtEpochMs - nowProvider()) / MILLIS_PER_HOUR
        channel.mutate {
            it.copy(
                auth = AuthUiState(
                    loggedIn = true,
                    rememberDevice = remainingHours > DEFAULT_SESSION_HOURS,
                    expiresAtEpochMs = stored.expiresAtEpochMs,
                ),
            )
        }
    }

    /**
     * 401 回调（ApiClient 在非登录请求收到 401 时调一次）。
     *
     * 必须"清 token + 明确提示"，不能静默：用户会以为是网络坏了，然后反复点同一个按钮；
     * 其实只要重新登录就能恢复。
     */
    fun handleUnauthorized() {
        clearSession()
        channel.mutate { it.copy(actionHint = "登录已过期，请重新登录") }
    }

    /**
     * 清掉本地会话。
     *
     * @param closeSheet 是否连"正在编辑的登录弹层"一起清掉。
     *   401/退出登录时不用（弹层本来就没开，或者用户可能正在填）；
     *   换服务器时必须清（那是上一台服务器的登录界面状态）。
     */
    fun clearSession(closeSheet: Boolean = false) {
        authTokenStore.clear()
        channel.mutate {
            if (closeSheet) {
                it.copy(
                    auth = AuthUiState(),
                    loginSheetOpen = false,
                    loginPassword = "",
                    loginState = LoginUiState.Idle,
                )
            } else {
                it.copy(auth = AuthUiState())
            }
        }
    }

    // 服务端"有没有设密码"由 /api/status 的 needsSetup 判定（服务端才知道有没有 passwordHash）。
    // 用它刷新标记，是为了让"这台服务器没设密码"的提醒在重启后依然成立。
    fun applyServerNeedsSetup(needsSetup: Boolean) {
        channel.mutate { it.copy(auth = it.auth.copy(noPassword = needsSetup)) }
    }
}

// 失败后的冷却时长：后端没有失败限流/锁定，1.5 秒是"能挡住连点穷举、又不至于让人觉得卡死"的折中。
private const val FAILED_LOGIN_COOLDOWN_MS = 1_500L

// 小时 → 毫秒，用于把本地保存的到期时刻换算成"剩余几小时"来判断当时勾没勾记住
private const val MILLIS_PER_HOUR = 3_600_000L

/**
 * 把登录失败翻译成一句能上屏的中文。
 *
 * 优先用服务端原文（例："密码错误"）：用户需要知道是密码错、还是根本没连上服务器。
 * 服务端没给文案时（例如 500、网关错误）退回数据层已经写好的中文 userMessage。
 */
private fun describeLoginFailure(error: ApiError): String {
    if (error is ApiError.Http && error.serverMessage.isNotBlank()) return error.serverMessage
    return error.userMessage
}
