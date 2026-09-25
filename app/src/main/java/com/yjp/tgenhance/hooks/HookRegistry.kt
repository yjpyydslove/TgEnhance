package com.yjp.tgenhance.hooks

import android.graphics.Typeface
import com.yjp.tgenhance.Prefs

/**
 * Hook 点的**唯一声明处**（v N2.0）。
 *
 * ### 为什么要有这张表
 *
 * N1 期间反复踩同一个坑：**同一条信息被两处各自维护**，改了一处忘了另一处，
 * 结果不会报错，只会「功能静默不对」：
 *
 *  - N1.7：挂载点 vs 自检清单 —— 有两个 Hook 点从没进自检，
 *    改名字时功能失效而自检照样全绿
 *  - N1.9：自检的判定标准（只看方法名）vs `HookFinder` 的过滤条件
 *    （还要看返回类型、参数个数）—— 官方改签名时两边说法互相矛盾
 *
 * 补丁打了两轮，但根还在：**「怎么找到这个 Hook 点」这件事仍然散在
 * 各 `*Hooks.kt` 的 `match(...)` 调用里，而自检另有一份手写清单。**
 *
 * 这张表把每个 Hook 点的定位信息收拢到一处：
 * 类从哪来、方法叫什么、过滤条件是什么、属于哪个功能、出错时怎么称呼它。
 * 挂载与自检都从这里读，**结构上不可能再对不上**。
 *
 * ### 与 [HookCatalog] 的分工
 *
 * - [HookCatalog]：**运行期计数**用的 key 与中文名（界面「运行状态」）
 * - [HookRegistry]：**挂载期定位**用的类、方法与过滤条件（本文件）
 *
 * 两者用同一个 `key` 字符串对应起来，`key` 也是互查的凭据。
 *
 * ### 本文件不引用任何 Xposed API
 *
 * 因此设置界面进程也能安全加载（自检报告要用到里面的中文名）。
 */
enum class TargetOwner {
    /** 类名写死在这里。 */
    FIXED,

    /** 类由客户端档案解析 —— fork 可能把主 Activity 搬到别处。 */
    LAUNCH_ACTIVITY,

    /** 类由客户端档案解析 —— Stories 相关类搬过好几次家。 */
    STORIES_CONTROLLER,

    /** 类由客户端档案解析 —— 设置页在不同版本/分支上路径不同。 */
    SETTINGS_FRAGMENT
}

/**
 * 一个 Hook 点的定位声明。
 *
 * @param key          与 [HookCatalog] 对应的统计 key
 * @param label        面向用户的中文名（自检报告里显示）
 * @param owner        类从哪里来
 * @param className    [TargetOwner.FIXED] 时使用的类名
 * @param names        精确方法名名单；空表示只靠特征匹配
 * @param returnType   要求的返回类型，`null` 表示不限
 * @param namePrefix   方法名前缀（忽略大小写）
 * @param nameContains 方法名需包含的子串（忽略大小写）
 * @param paramCount   参数个数（精确）
 * @param minParamCount 参数个数下限
 * @param maxParamCount 参数个数上限
 * @param featureKey   对应的功能开关 key；`null` 表示不属于任何可开关功能
 *                     （例如配置热更新），这类失败不该被标成「功能不可用」
 */
data class HookTarget(
    val key: String,
    val label: String,
    val owner: TargetOwner,
    val names: List<String> = emptyList(),
    val className: String = "",
    val returnType: Class<*>? = null,
    val namePrefix: String? = null,
    val nameContains: String? = null,
    val paramCount: Int? = null,
    val minParamCount: Int? = null,
    val maxParamCount: Int? = null,
    val featureKey: String? = null
) {
    /** 自检报告里显示用的类简名；类名未知时给出占位。 */
    val shortName: String
        get() = when (owner) {
            TargetOwner.FIXED -> className.substringAfterLast('.')
            TargetOwner.LAUNCH_ACTIVITY -> "LaunchActivity"
            TargetOwner.STORIES_CONTROLLER -> "StoriesController"
            TargetOwner.SETTINGS_FRAGMENT -> "SettingsActivity"
        }

    /** 自检要检查的方法名（精确名单为空时不检查具体方法）。 */
    val selfCheckNames: List<String> get() = names
}

object HookRegistry {

    /**
     * 全部 Hook 点。
     *
     * 顺序与 [HookCatalog] 保持一致，便于两边对照。
     */
    val ALL: List<HookTarget> = listOf(
        // ---------------- 多账号 ----------------
        HookTarget(
            key = "account.maxCount",
            label = "账号上限查询",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.UserConfig",
            names = listOf("getMaxAccountCount"),
            returnType = Int::class.javaPrimitiveType,
            nameContains = "maxaccount",
            featureKey = Prefs.ENABLE_ACCOUNT
        ),
        HookTarget(
            key = "account.expand",
            label = "账号数组扩容",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.AccountInstance",
            names = listOf("getInstance"),
            paramCount = 1,
            featureKey = Prefs.ENABLE_ACCOUNT
        ),
        HookTarget(
            key = "account.activatedCount",
            label = "已激活账号计数",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.UserConfig",
            names = listOf("getActivatedAccountsCount"),
            returnType = Int::class.javaPrimitiveType,
            nameContains = "activatedaccount",
            featureKey = Prefs.ENABLE_ACCOUNT
        ),
        HookTarget(
            key = "account.premiumCheck",
            label = "会员判定",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.UserConfig",
            names = listOf("hasPremiumOnAccounts"),
            returnType = Boolean::class.javaPrimitiveType,
            nameContains = "premium",
            featureKey = Prefs.ENABLE_ACCOUNT
        ),

        // ---------------- 界面与主题 ----------------
        HookTarget(
            key = "ui.typeface.seen",
            label = "字体加载请求",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.AndroidUtilities",
            names = listOf("getTypeface"),
            returnType = Typeface::class.java,
            featureKey = Prefs.SYSTEM_FONT
        ),
        HookTarget(
            key = "ui.stories",
            label = "Stories 显示查询",
            owner = TargetOwner.STORIES_CONTROLLER,
            names = listOf(
                "hasStories", "hasUnreadStories", "hasHiddenStories", "hasSelfStories"
            ),
            returnType = Boolean::class.javaPrimitiveType,
            namePrefix = "has",
            nameContains = "stor",
            featureKey = Prefs.HIDE_STORIES
        ),
        HookTarget(
            key = "ui.tablet",
            label = "平板布局判定",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.AndroidUtilities",
            // 两个都要挂：isTabletInternal() 内部会把结果缓存进静态字段，
            // 只改 isTabletForce() 的话首次调用之后就不再走原来那条路
            names = listOf("isTabletForce", "isTabletInternal"),
            returnType = Boolean::class.javaPrimitiveType,
            featureKey = Prefs.FORCE_TABLET
        ),
        HookTarget(
            key = "ui.updateCheck",
            label = "更新检查",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.SharedConfig",
            names = listOf("isAppUpdateAvailable"),
            returnType = Boolean::class.javaPrimitiveType,
            featureKey = Prefs.DISABLE_UPDATE_CHECK
        ),
        HookTarget(
            key = "ui.settingsEntry",
            label = "设置页入口注入",
            owner = TargetOwner.SETTINGS_FRAGMENT,
            names = listOf("fillItems", "onClick"),
            featureKey = Prefs.SETTINGS_ENTRY
        ),

        // ---------------- 网络 ----------------
        HookTarget(
            key = "net.proxyProbe",
            label = "代理连通性探测",
            owner = TargetOwner.FIXED,
            className = "org.telegram.tgnet.ConnectionsManager",
            names = listOf("checkProxy"),
            nameContains = "checkproxy",
            featureKey = Prefs.BLOCK_PROXY_PROBE
        ),
        HookTarget(
            key = "net.autoDownload.blocked",
            label = "拦截自动下载",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.DownloadController",
            names = listOf("canDownloadMedia"),
            featureKey = Prefs.BLOCK_AUTO_DOWNLOAD
        ),
        HookTarget(
            key = "net.autoplay.blocked",
            label = "拦截自动播放",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.SharedConfig",
            names = listOf("isAutoplayVideo", "isAutoplayGifs"),
            returnType = Boolean::class.javaPrimitiveType,
            featureKey = Prefs.DISABLE_AUTOPLAY
        ),

        // ---------------- 隐私与本地增强 ----------------
        HookTarget(
            key = "privacy.typing",
            label = "输入状态发送",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.MessagesController",
            names = listOf("sendTyping"),
            returnType = Boolean::class.javaPrimitiveType,
            nameContains = "typing",
            // sendTyping 至少带 dialogId + threadId + action 三个参数；
            // 加下限是为了排除同名但签名不同的辅助方法
            minParamCount = 3,
            featureKey = Prefs.HIDE_TYPING
        ),
        HookTarget(
            key = "privacy.deleteMessages",
            label = "消息删除",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.MessagesController",
            names = listOf("deleteMessages"),
            nameContains = "deletemessage",
            minParamCount = 2,
            featureKey = Prefs.ANTI_RECALL
        ),
        HookTarget(
            key = "privacy.readReceipt.blocked",
            label = "拦截已读上报",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.MessagesController",
            // private 方法；hookAllByName 按名字挂载、不看可见性，所以能挂上。
            // 拿「public 方法」当条件去搜会一直找不到它（N1.14 踩过）
            names = listOf("completeReadTask"),
            featureKey = Prefs.BLOCK_READ_RECEIPT
        ),
        HookTarget(
            key = "privacy.hideOnline",
            label = "隐藏在线状态",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.MessagesController",
            names = listOf("updateTimerProc"),
            featureKey = Prefs.HIDE_ONLINE
        ),
        HookTarget(
            key = "privacy.peerOnline",
            label = "隐藏对方在线",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.LocaleController",
            names = listOf("formatUserStatus"),
            featureKey = Prefs.HIDE_PEER_ONLINE
        ),
        HookTarget(
            key = "privacy.phoneMask",
            label = "手机号遮罩",
            owner = TargetOwner.FIXED,
            className = "org.telegram.PhoneFormat.PhoneFormat",
            names = listOf("format"),
            returnType = java.lang.String::class.java,
            paramCount = 1,
            featureKey = Prefs.HIDE_PHONE
        ),

        // ---------------- 广告屏蔽 ----------------
        HookTarget(
            key = "ads.sponsored.blocked",
            label = "屏蔽赞助消息",
            owner = TargetOwner.FIXED,
            className = "org.telegram.messenger.MessagesController",
            names = listOf("getSponsoredMessages"),
            nameContains = "sponsored",
            featureKey = Prefs.BLOCK_SPONSORED
        ),

        // ---------------- 框架 ----------------
        HookTarget(
            key = "prefs.reload",
            label = "配置热更新",
            owner = TargetOwner.LAUNCH_ACTIVITY,
            names = listOf("onResume", "onPause"),
            // 不属于任何可开关功能：它失效只是「改设置要重启」，
            // 不该被标成「当前客户端不支持某个功能」
            featureKey = null
        ),
    )

    /** 按统计 key 查一个 Hook 点。 */
    fun find(key: String): HookTarget? = ALL.firstOrNull { it.key == key }

    /**
     * 额外关联的统计 key（v N2.0）。
     *
     * 默认情况下注册表的 [HookTarget.key] 本身就是对应的统计 key。
     * 只有少数情况需要在这里补充：
     *
     *  - **一个挂载点驱动多个计数**：同一个回调里既统计 A 又统计 B。
     *    例如 `formatUserStatus` 那一个 Hook 点，同时驱动
     *    「隐藏对方在线」与「隐藏最后上线」两个计数。
     *  - **完全不计数**：见 [UNCOUNTED]。
     */
    private val EXTRA_STAT_KEYS: Map<String, List<String>> = mapOf(
        // 同一个 getTypeface 回调里分别统计「请求」与「替换」
        "ui.typeface.seen" to listOf("ui.typeface.replaced"),
        // deleteMessages 的同一个回调既统计删除、也统计拦截撤回
        "privacy.deleteMessages" to listOf("privacy.recall.blocked"),
        // 同一个 formatUserStatus 回调驱动两个计数
        "privacy.peerOnline" to listOf("privacy.peerStatus"),
    )

    /**
     * 不产生任何运行期计数的挂载点。
     *
     * 这几个用 `XC_MethodReplacement` 直接替换掉原实现 ——
     * 没有「原方法被调用」这个概念，计数也就无从谈起。
     * 它们依然要被自检盯着，所以留在注册表里。
     */
    private val UNCOUNTED: Set<String> = setOf(
        "account.activatedCount",
        "account.premiumCheck",
    )

    /**
     * 这个 Hook 点关联的全部运行期计数 key。
     *
     * 用来校验注册表与 [HookCatalog] 是否严丝合缝：每个计数值都能找到
     * 出处，每个挂载点也都知道自己该统计什么。
     */
    val HookTarget.statKeys: List<String>
        get() = if (key in UNCOUNTED) {
            emptyList()
        } else {
            listOf(key) + EXTRA_STAT_KEYS.getOrDefault(key, emptyList())
        }

    /** 全部挂载点关联到的统计 key（去重），供一致性检查使用。 */
    val coveredStatKeys: List<String>
        get() = ALL.flatMap { it.statKeys }.distinct().sorted()

    /**
     * 供自检遍历的清单：只要精确名单里有方法名的，就能逐条检查存在性。
     *
     * `names` 为空的目标（纯特征匹配）跳过 —— 那种没有固定名字可查。
     */
    val selfCheckTargets: List<HookTarget> get() = ALL.filter { it.names.isNotEmpty() }
}
