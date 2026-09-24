package com.yjp.tgenhance

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.yjp.tgenhance.core.Features
import de.robv.android.xposed.XSharedPreferences
import java.util.UUID

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
    const val BLOCK_AUTO_DOWNLOAD = "block_auto_download"
    const val DISABLE_AUTOPLAY = "disable_autoplay"

    // ---------------- 隐私与本地增强 ----------------
    const val ENABLE_PRIVACY = "enable_privacy"
    const val ANTI_RECALL = "anti_recall"
    const val HIDE_TYPING = "hide_typing"
    const val BLOCK_READ_RECEIPT = "block_read_receipt"
    const val HIDE_ONLINE = "hide_online"

    // ---------------- 反检测 ----------------
    const val HIDE_XPOSED = "hide_xposed"

    // ---------------- 诊断 ----------------
    const val ENABLE_DIAG = "enable_diag"

    /** 回传令牌：模块侧生成，hook 侧读出后随广播带回，用于过滤伪造来源。 */
    const val DIAG_TOKEN = "diag_token"

    /** 模块侧保存的最近一次运行期快照（来自 hook 端广播）。 */
    const val DIAG_SNAPSHOT = "diag_snapshot"
    const val DIAG_SNAPSHOT_AT = "diag_snapshot_at"

    // ---------------- 取值范围 ----------------
    const val DEF_MAX_ACCOUNTS = 6
    const val MAX_ACCOUNTS_LIMIT = 16
    const val MIN_ACCOUNTS_LIMIT = 3

    private const val RELOAD_THROTTLE_MS = 1_000L

    @Volatile
    private var lastReload = 0L

    @Volatile
    private var hookPrefs: XSharedPreferences? = null

    @Volatile
    private var appPrefs: SharedPreferences? = null

    /**
     * 全部布尔配置项。
     *
     * v3.0.0 起直接从 [Features] 注册表派生 —— 早先是手写第二份清单，
     * 新增开关时忘记同步就会导致该项进不了内存快照，只能回退直读文件。
     * 现在结构上不可能漏。
     */
    private val ALL_BOOLEAN_KEYS: List<String> get() = Features.ALL.map { it.key }

    private val ALL_INT_KEYS = listOf(MAX_ACCOUNTS)

    private val ALL_STRING_KEYS = listOf(DIAG_TOKEN)

    /**
     * 内存快照。
     *
     * Hook 回调可能每帧都在跑（`getTypeface` 尤其频繁），每次去读
     * `XSharedPreferences` 都要走一遍文件快照查找，没必要。
     * 这里把配置一次性读进 map，读取退化成一次哈希查找。
     *
     * 刷新时机：[initForHook] 与 [reload]，也就是「进程启动」和「用户切回 Telegram」，
     * 与热更新机制天然同步。
     */
    @Volatile
    private var snapshot: Map<String, Any>? = null

    private fun refreshSnapshot() {
        val p = hookPrefs ?: return
        try {
            val map = HashMap<String, Any>(32)
            for (key in ALL_BOOLEAN_KEYS) {
                // 只放「显式设置过」的项，未设置的交给调用方传的默认值决定
                if (p.contains(key)) map[key] = p.getBoolean(key, false)
            }
            for (key in ALL_INT_KEYS) {
                if (p.contains(key)) map[key] = p.getInt(key, DEF_MAX_ACCOUNTS)
            }
            for (key in ALL_STRING_KEYS) {
                p.getString(key, null)?.let { map[key] = it }
            }
            snapshot = map
        } catch (t: Throwable) {
            XLog.e("配置快照构建失败，回退为直接读取: ${t.message}")
            snapshot = null
        }
    }

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
        refreshSnapshot()
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
            refreshSnapshot()
        } catch (t: Throwable) {
            XLog.e("配置重载失败: ${t.message}")
        }
    }

    // ---------------- hook 端读取 ----------------
    //
    // 优先走内存快照（一次哈希查找），未命中再回退直读文件。
    // 快照在 initForHook / reload 时刷新，因此不会读到过期值。

    fun hookBoolean(key: String, def: Boolean): Boolean =
        (snapshot?.get(key) as? Boolean)
            ?: try {
                hookPrefs?.getBoolean(key, def) ?: def
            } catch (t: Throwable) {
                def
            }

    fun hookInt(key: String, def: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
        val raw = (snapshot?.get(key) as? Int)
            ?: try {
                hookPrefs?.getInt(key, def) ?: def
            } catch (t: Throwable) {
                def
            }
        return raw.coerceIn(min, max)
    }

    fun hookString(key: String, def: String): String =
        (snapshot?.get(key) as? String)
            ?: try {
                hookPrefs?.getString(key, def) ?: def
            } catch (t: Throwable) {
                def
            }

    /**
     * 模块进程：确保回传令牌已生成。
     *
     * hook 端会把该令牌放进诊断广播，设置界面据它判断来源是否可信；
     * 令牌只存在于模块私有配置里，别的应用伪造不出来。
     */
    fun ensureDiagToken(): String {
        val p = requireAppPrefs()
        val existing = p.getString(DIAG_TOKEN, null)
        if (!existing.isNullOrEmpty()) return existing
        val token = UUID.randomUUID().toString().replace("-", "")
        p.edit().putString(DIAG_TOKEN, token).apply()
        return token
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
    val blockAutoDownload: Boolean get() = hookBoolean(BLOCK_AUTO_DOWNLOAD, false)
    val disableAutoplay: Boolean get() = hookBoolean(DISABLE_AUTOPLAY, false)

    val antiRecall: Boolean get() = hookBoolean(ANTI_RECALL, false)
    val hideTyping: Boolean get() = hookBoolean(HIDE_TYPING, false)
    val blockReadReceipt: Boolean get() = hookBoolean(BLOCK_READ_RECEIPT, false)
    val hideOnline: Boolean get() = hookBoolean(HIDE_ONLINE, false)

    /** 反检测：隐藏模块与 Xposed 框架的痕迹。 */
    val hideXposed: Boolean get() = hookBoolean(HIDE_XPOSED, false)

    /** hook 侧读取回传令牌；为空说明用户还没打开过设置界面。 */
    val diagToken: String get() = hookString(DIAG_TOKEN, "")

    // ---------------- 配置导入 / 导出 ----------------

    /**
     * 导出格式的标识前缀。
     *
     * 带版本号是给**将来**留的退路：哪天真要重命名某个配置 key，
     * 靠这个前缀就能识别出旧格式并做迁移，而不是让老用户的配置直接失效。
     *
     * 用明文 `key=value;` 逐项拼接、不做 Base64 —— 用户可能想手工改一两项再导入，
     * 也可能要贴到聊天里同步到另一台设备，可读比紧凑重要。
     */
    private const val CONFIG_PREFIX = "TGE2:"

    /** v3.6.0 及更早的导出前缀。字段结构相同，仅前缀不同，导入时一并接受。 */
    private const val CONFIG_PREFIX_LEGACY = "TGE1:"

    /** 导入文本的长度上限，防止误把一大段无关内容粘进来后做无谓解析。 */
    private const val MAX_IMPORT_LENGTH = 8192

    /** 参与导入导出的布尔项。 */
    private val BOOLEAN_KEYS get() = ALL_BOOLEAN_KEYS

    /** 参与导入导出的数值项。 */
    private val INT_KEYS get() = ALL_INT_KEYS

    /** 序列化当前配置。 */
    fun exportFrom(p: SharedPreferences): String = buildString {
        append(CONFIG_PREFIX)
        for (key in BOOLEAN_KEYS) {
            append(key).append('=').append(if (p.getBoolean(key, false)) 1 else 0).append(';')
        }
        for (key in INT_KEYS) {
            append(key).append('=').append(p.getInt(key, DEF_MAX_ACCOUNTS)).append(';')
        }
    }

    /**
     * 解析并写入配置。
     *
     * @return 成功写入的项数；`-1` 表示前缀不识别、内容过长或格式无法解析。
     *         未知的 key 会被忽略而不是报错，便于跨版本传递配置。
     */
    fun importTo(p: SharedPreferences, raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_IMPORT_LENGTH) return -1

        val body = when {
            text.startsWith(CONFIG_PREFIX) -> text.removePrefix(CONFIG_PREFIX)
            text.startsWith(CONFIG_PREFIX_LEGACY) -> text.removePrefix(CONFIG_PREFIX_LEGACY)
            else -> return -1
        }

        val editor = p.edit()
        var applied = 0
        for (part in body.split(';')) {
            if (part.isBlank()) continue
            val sep = part.indexOf('=')
            if (sep <= 0) continue
            val key = part.substring(0, sep).trim()
            val value = part.substring(sep + 1).trim()
            when (key) {
                in BOOLEAN_KEYS -> {
                    // 只认 1/0，其他值不猜
                    if (value == "1" || value == "0") {
                        editor.putBoolean(key, value == "1")
                        applied++
                    }
                }
                in INT_KEYS -> {
                    value.toIntOrNull()?.let {
                        editor.putInt(key, it.coerceIn(MIN_ACCOUNTS_LIMIT, MAX_ACCOUNTS_LIMIT))
                        applied++
                    }
                }
            }
        }
        editor.apply()
        return applied
    }
}
