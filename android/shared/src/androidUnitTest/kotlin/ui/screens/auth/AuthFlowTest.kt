// 登录底座的状态机测试：token 的存、清、过期判定、401 拦截、换地址清理。
//
// 为什么必须测这些：它们全都是"看不见的错"——
// - token 没存上 → 用户每次进来都要重登，看起来像"登录没生效"；
// - 该清没清 → 之后每个带凭证的请求都 401，而界面只会说"加载失败"；
// - 过期没判 → 用户以为还登着，点收藏才被服务端拒绝。
// 这些在真机上都要反复清数据/等过期才能试，用假时钟 + 假存储可以秒级钉死。

package ui.screens.auth

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.AuthTokenStore
import com.eightfs.lumigather.data.HttpRawResponse
import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.StoredAuthToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ui.screens.download.DownloadFailureKind
import ui.screens.download.DownloadResult
import ui.screens.download.ImageDownloader
import ui.screens.settings.ServerAddressStore
import ui.screens.state.AuthUiState
import ui.screens.state.HomeStateHolder
import ui.screens.state.LoginUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 固定"现在"：2024-01-01T00:00:00Z。用固定时钟才能断言"有效期正好 30 天"这类事实。
private const val NOW_MS = 1_704_067_200_000L
private const val ONE_HOUR_MS = 3_600_000L

private const val SITE_CONFIG_JSON = """
{"title":"拾光集","fsRoot":"/root","imagesPerRow":20,
 "categories":[{"name":"崩坏3","label":"崩坏3","color":"#c084fc"}],
 "directories":[{"path":"test-root/honkai","category":"崩坏3"}]}
"""

private const val IMAGES_JSON = """
{"path":"test-root/honkai","root":"test-root/honkai","recursive":true,"count":0,"total":0,
 "offset":0,"limit":20,"returned":0,"images":[]}
"""

// 内存地址存储：可预置地址
private class FixedAddressStore(private val address: String) : ServerAddressStore {
    private var current = address
    override fun read(): String = current
    override fun write(address: String) {
        current = address
    }
}

// 内存 token 存储：断言"存了什么/清没清"
private class MemoryAuthTokenStore(initial: StoredAuthToken? = null) : AuthTokenStore {
    private var stored: StoredAuthToken? = initial
    override fun read(): StoredAuthToken? = stored
    override fun write(token: String, expiresAtEpochMs: Long) {
        stored = StoredAuthToken(token, expiresAtEpochMs)
    }
    override fun clear() {
        stored = null
    }
}

// 下载桩：本文件不测下载
private object NoopDownloader : ImageDownloader {
    override suspend fun saveToGallery(imageUrl: String, fileName: String): DownloadResult =
        DownloadResult.Failure(DownloadFailureKind.Unknown, "测试不下载")
}

/**
 * 可控的假传输层：
 * - /api/lumigather 永远 200（让 bootstrap 能进主界面）
 * - /api/status 由 [statusCode] 决定（用来构造 401）
 * - /api/login 返回 [loginCode]/[loginBody]
 * - /api/logout 记录 Authorization 头
 */
private class AuthTransport(
    private val loginCode: Int = 200,
    private val loginBody: String = """{"ok":true,"token":"tok-1","cookieName":"lumigather_session"}""",
    private val statusCode: Int = 200,
    private val statusBody: String = """{"ok":true,"version":"1.0.3","needsAuth":true,"authed":true}""",
) : HttpTransport {

    var lastLoginRequestBody: String? = null
    var lastLogoutAuthorization: String? = null

    override suspend fun get(url: String, query: Map<String, String>, timeoutMs: Long): HttpRawResponse =
        when {
            url.endsWith("/api/lumigather") -> HttpRawResponse(200, SITE_CONFIG_JSON, "application/json")
            url.endsWith("/api/images") -> HttpRawResponse(200, IMAGES_JSON, "application/json")
            url.endsWith("/api/status") -> HttpRawResponse(statusCode, statusBody, "application/json")
            else -> HttpRawResponse(404, "{}", "application/json")
        }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpRawResponse = when {
        url.endsWith("/api/login") -> {
            lastLoginRequestBody = body
            HttpRawResponse(loginCode, loginBody, "application/json")
        }
        url.endsWith("/api/logout") -> {
            lastLogoutAuthorization = headers["Authorization"]
            HttpRawResponse(200, """{"ok":true}""", "application/json")
        }
        else -> HttpRawResponse(404, "{}", "application/json")
    }
}

private fun holder(
    transport: HttpTransport,
    tokenStore: AuthTokenStore = MemoryAuthTokenStore(),
    addressStore: ServerAddressStore = FixedAddressStore("http://h:8081"),
    now: Long = NOW_MS,
): HomeStateHolder = HomeStateHolder(
    clientFactory = { address, onUnauthorized ->
        ApiClient(address, transport, onUnauthorized = onUnauthorized)
    },
    addressStore = addressStore,
    imageDownloader = NoopDownloader,
    authTokenStore = tokenStore,
    nowProvider = { now },
)

class LoginFlowTest {

    @Test
    fun loginSuccessStoresTokenWithThirtyDayExpiryAndClosesSheet() = runBlocking {
        val tokenStore = MemoryAuthTokenStore()
        val transport = AuthTransport()
        val stateHolder = holder(transport, tokenStore)
        stateHolder.bootstrap()

        stateHolder.openLoginSheet()
        stateHolder.updateLoginPassword("s3cret")
        stateHolder.submitLogin()

        val state = stateHolder.state
        assertTrue(state.auth.loggedIn, "登录成功应变为已登录")
        assertFalse(state.loginSheetOpen, "成功后应关闭弹层")
        assertEquals("", state.loginPassword, "密码不能留在状态里")
        // 默认勾选「记住此设备」→ 30 天
        assertEquals(NOW_MS + 720 * ONE_HOUR_MS, state.auth.expiresAtEpochMs)
        assertEquals("登录成功", state.actionHint)

        // token 落盘，且到期时刻与状态一致
        val stored = assertNotNull(tokenStore.read(), "登录后必须落盘 token")
        assertEquals("tok-1", stored.token)
        assertEquals(NOW_MS + 720 * ONE_HOUR_MS, stored.expiresAtEpochMs)

        // 请求体里确实带了 remember=true 与密码（证明走的是正确契约）
        // 注意：Kuikly 的 JSONObject.toString() 会在冒号后带空格（{"password": "x"}），
        // 所以这里用正则容错空白，而不是死盯某一种排版。
        val body = assertNotNull(transport.lastLoginRequestBody)
        assertTrue(
            Regex("\"remember\"\\s*:\\s*true").containsMatchIn(body),
            "默认应带上 remember=true，实际：$body",
        )
        assertTrue(body.contains("s3cret"), "请求体应包含密码（服务端要校验它）")
    }

    @Test
    fun loginWithoutRememberUsesThreeDayExpiry() = runBlocking {
        val tokenStore = MemoryAuthTokenStore()
        val stateHolder = holder(AuthTransport(), tokenStore)
        stateHolder.bootstrap()

        stateHolder.openLoginSheet()
        stateHolder.toggleLoginRemember(false)
        stateHolder.updateLoginPassword("pw")
        stateHolder.submitLogin()

        assertEquals(NOW_MS + 72 * ONE_HOUR_MS, stateHolder.state.auth.expiresAtEpochMs)
        assertEquals("已登录 · 3 天内有效", stateHolder.state.authSummary)
    }

    @Test
    fun wrongPasswordShowsServerMessageInlineAndBlocksRetryDuringCooldown() = runBlocking {
        val transport = AuthTransport(
            loginCode = 401,
            loginBody = """{"ok":false,"error":"密码错误"}""",
        )
        val tokenStore = MemoryAuthTokenStore()
        val stateHolder = holder(transport, tokenStore)
        stateHolder.bootstrap()
        stateHolder.openLoginSheet()
        stateHolder.updateLoginPassword("wrong")

        // 在独立协程里跑，好在冷却期内观察状态
        val job = launch(Dispatchers.Default) { stateHolder.submitLogin() }
        delay(100) // 远小于 1.5 秒冷却

        val duringCooldown = stateHolder.state
        assertTrue(duringCooldown.loginBlocked, "失败后应进入冷却期")
        assertFalse(duringCooldown.loginSubmittable, "冷却期按钮不可提交")
        assertEquals("密码错误", (duringCooldown.loginState as LoginUiState.Failed).message)

        job.join()

        assertFalse(stateHolder.state.loginBlocked, "冷却结束后应恢复可提交")
        assertFalse(stateHolder.state.auth.loggedIn)
        assertNull(tokenStore.read(), "失败不应写入 token")
    }

    @Test
    fun noPasswordServerAllowsEmptyPasswordAndShowsNotice() = runBlocking {
        val transport = AuthTransport(
            loginCode = 200,
            loginBody = """{"ok":true,"noPassword":true,"token":"tok-nopass","cookieName":"c","message":"未设置访问密码"}""",
            // needsSetup=true 表示服务端还没设密码：状态层据此允许"空密码也可提交"
            statusBody = """{"ok":true,"version":"1.0.3","needsSetup":true,"needsAuth":false,"authed":false}""",
        )
        val tokenStore = MemoryAuthTokenStore()
        val stateHolder = holder(transport, tokenStore)
        stateHolder.bootstrap()

        assertTrue(stateHolder.state.auth.noPassword, "needsSetup=true 时应标记为未设密码服务器")

        stateHolder.openLoginSheet()
        // 不填密码直接提交：未设密码的服务器会放行，客户端不该在这种服务器上逼用户乱填
        assertTrue(stateHolder.state.loginSubmittable, "未设密码的服务器应允许空密码提交")
        stateHolder.submitLogin()

        assertTrue(stateHolder.state.auth.loggedIn, "noPassword 也算登录成功")
        assertTrue(stateHolder.state.auth.noPassword)
        assertTrue(
            stateHolder.state.actionHint?.contains("未设置密码") == true,
            "应提示用户尽快设置密码，实际：${stateHolder.state.actionHint}",
        )
        assertEquals("tok-nopass", tokenStore.read()?.token)
    }

    @Test
    fun logoutCallsServerAndClearsLocalToken() = runBlocking {
        val tokenStore = MemoryAuthTokenStore()
        val transport = AuthTransport()
        val stateHolder = holder(transport, tokenStore)
        stateHolder.bootstrap()
        stateHolder.openLoginSheet()
        stateHolder.updateLoginPassword("pw")
        stateHolder.submitLogin()
        assertTrue(stateHolder.state.auth.loggedIn)

        stateHolder.logout()

        assertFalse(stateHolder.state.auth.loggedIn)
        assertNull(tokenStore.read(), "退出后本地 token 必须清掉")
        assertEquals("Bearer tok-1", transport.lastLogoutAuthorization, "登出应带上 Bearer token")
    }
}

class SessionBoundaryTest {

    @Test
    fun unauthorizedResponseClearsTokenAndAsksRelogin() = runBlocking {
        // 预置一个"有效"的 token，但服务端对 /api/status 回 401
        val tokenStore = MemoryAuthTokenStore(StoredAuthToken("old-token", NOW_MS + 10 * ONE_HOUR_MS))
        val transport = AuthTransport(
            statusCode = 401,
            statusBody = """{"ok":false,"error":"未登录"}""",
        )
        val stateHolder = holder(transport, tokenStore)

        stateHolder.bootstrap()

        assertFalse(stateHolder.state.auth.loggedIn, "401 之后应视为未登录")
        assertNull(tokenStore.read(), "401 必须清掉本地 token")
        assertTrue(
            stateHolder.state.actionHint?.contains("登录已过期") == true,
            "要明确提示重新登录，不能静默，实际：${stateHolder.state.actionHint}",
        )
    }

    @Test
    fun changingServerAddressClearsToken() = runBlocking {
        val tokenStore = MemoryAuthTokenStore(StoredAuthToken("old-token", NOW_MS + 10 * ONE_HOUR_MS))
        val addressStore = FixedAddressStore("http://h:8081")
        val stateHolder = holder(AuthTransport(), tokenStore, addressStore)
        stateHolder.bootstrap()
        assertTrue(stateHolder.state.auth.loggedIn, "前提：先处于已登录")

        stateHolder.openSettings()
        stateHolder.updateAddressDraft("http://other:8081")
        stateHolder.saveAddress()

        assertNull(tokenStore.read(), "换服务器必须清 token（旧 token 对新服务器无效）")
        assertEquals(AuthUiState(), stateHolder.state.auth)
    }

    @Test
    fun expiredStoredTokenIsTreatedAsLoggedOut() = runBlocking {
        // 到期时刻在过去
        val tokenStore = MemoryAuthTokenStore(StoredAuthToken("stale", NOW_MS - ONE_HOUR_MS))
        val stateHolder = holder(AuthTransport(), tokenStore)

        stateHolder.bootstrap()

        assertFalse(stateHolder.state.auth.loggedIn)
        assertNull(tokenStore.read(), "过期 token 应被清掉，不要每次启动都带着坏值")
    }

    @Test
    fun unreadableTokenIsTreatedAsLoggedOut() = runBlocking {
        // 存储返回 null（真机上对应"空串/缺到期时间"这类脏数据）
        val stateHolder = holder(AuthTransport(), MemoryAuthTokenStore(null))

        stateHolder.bootstrap()

        assertFalse(stateHolder.state.auth.loggedIn)
        assertEquals("未登录", stateHolder.state.authSummary)
    }

    @Test
    fun favoriteOpensLoginSheetWhenLoggedOutAndShowsComingSoonWhenLoggedIn() = runBlocking {
        val tokenStore = MemoryAuthTokenStore()
        val stateHolder = holder(AuthTransport(), tokenStore)
        stateHolder.bootstrap()

        // 未登录：点收藏 → 打开登录弹层（不去做假的收藏态）
        stateHolder.requestFavorite()
        assertTrue(stateHolder.state.loginSheetOpen, "未登录点收藏应弹登录")
        assertNull(stateHolder.state.actionHint, "此时不该出现任何收藏反馈")

        // 登录后：提示功能即将开放
        stateHolder.closeLoginSheet()
        stateHolder.openLoginSheet()
        stateHolder.updateLoginPassword("pw")
        stateHolder.submitLogin()
        stateHolder.requestFavorite()

        assertEquals("收藏功能即将开放", stateHolder.state.actionHint)
        assertFalse(stateHolder.state.loginSheetOpen)
    }
}
