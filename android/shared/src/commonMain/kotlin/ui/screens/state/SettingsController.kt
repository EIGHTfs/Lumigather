// 设置子状态机：服务器地址的读写、连接测试、设置界面的进与出。
//
// 它管的是一个"线性流程"（填地址 → 测试 → 保存），但保存这一步有跨功能的副作用：
// 换服务器后旧 token 作废、旧分类与图片全要清掉、内容要整体重载。这些都不是设置该知道的事，
// 所以两个显式回调交给 facade 编排：
//   - [onAddressCommitted]（同步）：地址已落盘，请把其它子状态机里属于旧服务器的痕迹清掉；
//   - [onReloadRequired]（挂起）：清完了，请用新地址重新加载内容。
// 这样设置只知道"我做完了两件事要通知"，不知道谁在响应，依赖方向单向。

package ui.screens.state

import com.eightfs.lumigather.data.ApiClient
import com.eightfs.lumigather.data.ApiResult
import ui.screens.settings.ServerAddressRules
import ui.screens.settings.ServerAddressStore

class SettingsController(
    private val channel: StateChannel,
    private val addressStore: ServerAddressStore,
    // 造临时 client 用（测连接不该污染正在显示的旧数据）
    private val clientFactory: (String, () -> Unit) -> ApiClient,
    // 临时 client 的 401 回调：连的可能是另一台服务器，凭证失效同样要清本地 token
    private val onUnauthorized: () -> Unit,
    private val onAddressCommitted: (String) -> Unit,
    private val onReloadRequired: suspend () -> Unit,
) {

    // 运行中打开设置：预填当前地址，方便用户只改端口这类小改动。
    // 侧栏的开合不在这里改 —— 那是侧栏子状态机的字段，由 facade 在调用本方法前后收栏。
    fun openSettings() {
        val savedAddress = addressStore.read()
        channel.mutate {
            it.copy(
                settingsOpen = true,
                addressDraft = savedAddress,
                addressError = null,
                testState = ConnectionTestState.Idle,
                hasSavedAddress = savedAddress.isNotBlank(),
            )
        }
    }

    fun updateAddressDraft(address: String) {
        channel.mutate { it.copy(addressDraft = address, addressError = null) }
    }

    /**
     * 只测试不保存：用户可能只是想确认"地址对不对"，没打算立刻切换。
     *
     * 用临时 ApiClient 而不是正式仓库：测试失败不该影响正在显示的旧数据。
     */
    suspend fun testAddress() {
        val draft = channel.state.addressDraft
        val normalized = ServerAddressRules.normalize(draft)
        if (normalized == null) {
            channel.mutate {
                it.copy(
                    addressError = ServerAddressRules.errorMessageFor(draft),
                    testState = ConnectionTestState.Idle,
                )
            }
            return
        }

        channel.mutate { it.copy(addressError = null, testState = ConnectionTestState.Testing) }
        val result = clientFactory(normalized, onUnauthorized).fetchStatus()
        channel.mutate { current ->
            current.copy(
                testState = when (result) {
                    is ApiResult.Ok -> ConnectionTestState.Success(result.value.version)
                    is ApiResult.Err -> ConnectionTestState.Failure(result.error.userMessage)
                },
            )
        }
    }

    /**
     * 保存并立即生效：校验 → 持久化 → 通知清理旧服务器痕迹 → 重载内容。
     *
     * "立即生效"是这个功能的重点：改完地址要求用户重启应用，等于把配置藏起来。
     */
    suspend fun saveAddress() {
        val draft = channel.state.addressDraft
        val normalized = ServerAddressRules.normalize(draft)
        if (normalized == null) {
            channel.mutate { it.copy(addressError = ServerAddressRules.errorMessageFor(draft)) }
            return
        }

        addressStore.write(normalized)
        channel.mutate {
            it.copy(
                settingsOpen = false,
                hasSavedAddress = true,
                addressDraft = normalized,
                addressError = null,
                testState = ConnectionTestState.Idle,
            )
        }
        // 同步清理（旧 token / 旧分类 / 旧搜索词），再整体重载。
        // 两步之间没有挂起点，所以 UI 看不到"地址已换但还显示旧图"的中间态。
        onAddressCommitted(normalized)
        onReloadRequired()
    }

    // 从设置界面退回主界面。没配过地址时不允许退：退出去也没有内容可看。
    fun closeSettings() {
        if (!channel.state.hasSavedAddress) return
        channel.mutate {
            it.copy(settingsOpen = false, addressError = null, testState = ConnectionTestState.Idle)
        }
    }

    // 冷启动且没有可用地址：直接停在设置界面（这是唯一可做的事）
    fun enterSettingsMode(hasSavedAddress: Boolean) {
        channel.mutate {
            it.copy(
                isLoadingCategories = false,
                settingsOpen = true,
                addressDraft = "",
                addressError = null,
                testState = ConnectionTestState.Idle,
                hasSavedAddress = hasSavedAddress,
            )
        }
    }

    // 浏览子状态机成功拉到分类后告知"这台服务器现在可用了"，避免两个子状态机各写同一个字段
    fun markAddressSaved() {
        channel.mutate { it.copy(hasSavedAddress = true) }
    }
}
