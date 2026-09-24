package com.yjp.tgenhance

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences

/**
 * 配置项读写。
 *
 * 进程模型：
 *  - 模块 App 进程（设置界面）：普通 SharedPreferences 读写。
 *  - Telegram 进程（hook 端）：只读 XSharedPreferences，
 *    依赖 AndroidManifest 中 `xposedsharedprefs=true` 让 LSPosed 暴露该 prefs 文件。
 *
 * 因此修改设置后需要重启 Telegram 才能让 hook 端读到新值，这是设计使然
 * （避免 hook 端运行期反复 reload 带来的抖动与不一致）。
 */
object Prefs {

    const val MODULE_PKG = "com.yjp.tgenhance"
    const val FILE = "tgenhance_prefs"

    // ---------------- 多账号 ----------------
    const val ENABLE_ACCOUNT = "enable_account"
    const val MAX_ACCOUNTS = "max_accounts"

    // ---------------- 界面与主题 ----------------
    const val ENABLE_UI = "enable_ui"
    const val SYSTEM_FONT = "system_font"
    const val HIDE_STORIES = "hide_stories"

    // ---------------- 网络 ----------------
    const val ENABLE_NET = "enable_net"
    const val BLOCK_PROXY_PROBE = "block_proxy_probe"
    const val NET_TIMEOUT_SCALE = "net_timeout_scale"

    // ---------------- 隐私与本地增强 ----------------
    const val ENABLE_PRIVACY = "enable_privacy"
    const val ANTI_RECALL = "anti_recall"
    const val HIDE_TYPING = "hide_typing"
    const val BLOCK_READ_RECEIPT = "block_read_receipt"

    // ---------------- 诊断 ----------------
    const val ENABLE_DIAG = "enable_diag"

    // ---------------- 取值范围 ----------------
    const val DEF_MAX_ACCOUNTS = 6
    const val MAX_ACCOUNTS_LIMIT = 16
    const val MIN_ACCOUNTS_LIMIT = 3

    const val DEF_TIMEOUT_SCALE = 100
    const val MIN_TIMEOUT_SCALE = 50
    const val MAX_TIMEOUT_SCALE = 400

    private const val RELOAD_THROTTLE_MS = 1_000L

    @Volatile
    private var lastReload = 0L

    @Volatile
    private var hookPrefs: XSharedPreferences? = null

    @Volatile
    private var appPrefs: SharedPreferences? = null

    /** Telegram 进程内初始化（hook 端只读）。 */
    fun initForHook() {
        hookPrefs = try {
            XSharedPreferences(MODULE_PKG, FILE).also {
                runCatching { it.makeWorldReadable() }
            }
        } catch (t: Throwable) {
            XLog.e("XSharedPreferences 初始化失败: ${t.message}")
            null
        }
    }

    /** 设置界面初始化（模块 App 进程内读写）。 */
    fun initForApp(context: Context) {
        appPrefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun requireAppPrefs(): SharedPreferences =
        appPrefs ?: throw IllegalStateException("Prefs.initForApp() 未调用")

    /**
     * 让 hook 端重新读取配置文件。
     *
     * v2.0.0 起 hook 全部**常驻挂载**，开关判断放在回调里实时读取，
     * 因此只要调用本方法刷新一次，多数设置无需重启 Telegram 即可生效
     * （LSPosed 给的是文件快照，不 reload 的话读到的还是启动时的旧值）。
     *
     * 节流 1 秒：Activity 密集 resume 时不必反复读盘。
     */
    fun reload() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReload < RELOAD_THROTTLE_MS) return
        lastReload = now
        try {
            hookPrefs?.reload()
        } catch (t: Throwable) {
            XLog.e("配置重载失败: ${t.message}")
        }
    }

    // ---------------- hook 端读取 ----------------

    fun hookBoolean(key: String, def: Boolean): Boolean =
        try {
            hookPrefs?.getBoolean(key, def) ?: def
        } catch (t: Throwable) {
            def
        }

    fun hookInt(key: String, def: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int =
        try {
            (hookPrefs?.getInt(key, def) ?: def).coerceIn(min, max)
        } catch (t: Throwable) {
            def
        }

    // ---------------- 语义化访问 ----------------

    val accountEnabled: Boolean get() = hookBoolean(ENABLE_ACCOUNT, false)
    val uiEnabled: Boolean get() = hookBoolean(ENABLE_UI, false)
    val netEnabled: Boolean get() = hookBoolean(ENABLE_NET, false)
    val privacyEnabled: Boolean get() = hookBoolean(ENABLE_PRIVACY, false)
    val diagEnabled: Boolean get() = hookBoolean(ENABLE_DIAG, true)

    val maxAccounts: Int
        get() = hookInt(MAX_ACCOUNTS, DEF_MAX_ACCOUNTS, MIN_ACCOUNTS_LIMIT, MAX_ACCOUNTS_LIMIT)

    val systemFont: Boolean get() = hookBoolean(SYSTEM_FONT, false)
    val hideStories: Boolean get() = hookBoolean(HIDE_STORIES, false)

    val blockProxyProbe: Boolean get() = hookBoolean(BLOCK_PROXY_PROBE, false)
    val netTimeoutScale: Int
        get() = hookInt(NET_TIMEOUT_SCALE, DEF_TIMEOUT_SCALE, MIN_TIMEOUT_SCALE, MAX_TIMEOUT_SCALE)

    val antiRecall: Boolean get() = hookBoolean(ANTI_RECALL, false)
    val hideTyping: Boolean get() = hookBoolean(HIDE_TYPING, false)
    val blockReadReceipt: Boolean get() = hookBoolean(BLOCK_READ_RECEIPT, false)
}
