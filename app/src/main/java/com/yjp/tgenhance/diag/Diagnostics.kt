package com.yjp.tgenhance.diag

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.core.Features
import com.yjp.tgenhance.hooks.ClientProfileDetector
import com.yjp.tgenhance.hooks.ConflictWatch
import com.yjp.tgenhance.hooks.HookFinder
import com.yjp.tgenhance.hooks.HookStatus
import de.robv.android.xposed.XposedHelpers

/**
 * 挂载期自检报告。
 *
 * Hook 点强依赖 Telegram 的类名 / 方法签名，而这些会随版本变动或混淆策略调整而失效。
 * 为了让「适配新版本」不依赖反复试错，模块启动时主动探测所有关键类与方法是否存在。
 *
 * v2.3.0 起结果不再只进日志，而是**结构化成清单**并随诊断广播回传（见 [Diagnostics.report]），
 * 设置界面里可以直接看到哪一项没匹配上 —— 不必再离开应用去翻 LSPosed 日志。
 */
object Diagnostics {

    /** 一项自检结果。 */
    data class CheckItem(
        /** 类简名，例如 `UserConfig`。 */
        val owner: String,
        /** 成员签名，例如 `getMaxAccountCount()`；类缺失时为「类缺失」。 */
        val member: String,
        /** 是否命中。 */
        val ok: Boolean,
        /**
         * 未命中时给出的同类方法候选名。
         *
         * 官方把 `hasStories` 改成 `hasNewStories` 这类改动，报错本身毫无线索，
         * 翻源码又得先知道去哪个类翻。直接把候选列出来，一眼就能看出新名字。
         */
        val suggestions: List<String> = emptyList()
    )

    private data class Target(val className: String, val methods: List<String>)

    /**
     * 待探测清单。新增 Hook 点时同步加到这里，否则自检会漏项。
     */
    private val TARGETS = listOf(
        Target(
            "org.telegram.messenger.UserConfig",
            listOf(
                "getMaxAccountCount", "getInstance",
                "getActivatedAccountsCount", "hasPremiumOnAccounts"
            )
        ),
        Target(
            "org.telegram.messenger.AccountInstance",
            listOf("getInstance")
        ),
        Target(
            "org.telegram.messenger.MessagesController",
            listOf("deleteMessages", "sendTyping", "completeReadTask", "getSponsoredMessages")
        ),
        Target(
            "org.telegram.messenger.LocaleController",
            listOf("formatUserStatus")
        ),
        Target(
            "org.telegram.messenger.AndroidUtilities",
            listOf("getTypeface", "isTabletForce")
        ),
        Target(
            "org.telegram.messenger.SharedConfig",
            listOf("isAutoplayVideo", "isAutoplayGifs", "isAppUpdateAvailable")
        ),
        Target(
            "org.telegram.PhoneFormat.PhoneFormat",
            listOf("format")
        ),
        Target(
            "org.telegram.tgnet.ConnectionsManager",
            listOf("checkProxy")
        ),
        Target(
            "org.telegram.messenger.DownloadController",
            listOf("canDownloadMedia")
        ),
        Target(
            "org.telegram.ui.LaunchActivity",
            listOf("onResume", "onPause")
        ),
        // StoriesController 与主 Activity 不写死路径：各 fork 可能把它们搬到别处，
        // 由 ClientProfileDetector 解析出实际类名后再检查（见 run）
    )

    /** Stories 相关查询方法，用于对解析出的 StoriesController 做自检。 */
    private val STORIES_METHODS = listOf(
        "hasStories", "hasUnreadStories", "hasHiddenStories", "hasSelfStories"
    )

    @Volatile
    private var lastReport: List<CheckItem> = emptyList()

    /** 最近一次自检结果；未执行过时为空列表。 */
    fun report(): List<CheckItem> = lastReport

    fun run(classLoader: ClassLoader) {
        XLog.section("诊断报告开始")
        val items = ArrayList<CheckItem>()

        // 客户端信息放最前面：它是解读后面所有结果的上下文
        val profile = ClientProfileDetector.current()
        if (profile != null) {
            val outdated = profile.isBelowSupportedVersion()
            items += CheckItem(
                owner = "客户端",
                member = profile.summary(),
                ok = profile.launchActivity != null &&
                    profile.storiesController != null && !outdated,
                suggestions = ArrayList<String>().apply {
                    if (outdated) add("客户端版本偏旧：部分功能在本版上不可用")
                    if (profile.launchActivity == null) add("未识别到主 Activity：配置热更新退回系统 Activity")
                    if (profile.storiesController == null) add("未识别到 StoriesController：隐藏 Stories 不可用")
                }
            )
            // 用解析出的真实类路径做自检，而不是写死路径
            profile.launchActivity?.let {
                items += check(classLoader, Target(it, listOf("onResume", "onPause")))
            }
            profile.storiesController?.let {
                items += check(classLoader, Target(it, STORIES_METHODS))
            }
        }

        for (target in TARGETS) {
            items += check(classLoader, target)
        }
        configConsistency()?.let { items += it }
        conflictCheck()?.let { items += it }
        availabilityCheck()?.let { items += it }
        installFailureCheck()?.let { items += it }
        fuzzyMatchCheck()?.let { items += it }
        items += frameworkCheck()
        // 有问题的项排前面：自检结果动辄十几行，没人会逐行读完，
        // 把「需要处理的」顶到最上面，比按类别整齐排列更有用
        lastReport = items.sortedBy { if (it.ok) 1 else 0 }

        val okCount = items.count { it.ok }
        val classMissing = items.count { !it.ok && it.member == MISSING_CLASS }
        XLog.result(
            "诊断",
            "自检完成：$okCount/${items.size} 项命中" +
                if (classMissing > 0) "，其中 $classMissing 项因类不存在而缺失" else ""
        )
        if (okCount < items.size) {
            XLog.w("[诊断] 未命中的项见上方逐条输出；设置界面的「运行状态」里也能直接看到")
            for (item in items.filter { !it.ok && it.suggestions.isNotEmpty() }) {
                XLog.result(
                    "诊断",
                    "${item.owner}.${item.member} 的候选新名字: ${item.suggestions.joinToString(", ")}"
                )
            }
        }
        XLog.section("诊断报告结束")
    }

    /**
     * 配置一致性检查：找出配置文件里存在、但已无对应功能的 key。
     *
     * 这类残留来自「功能删了、配置还在」，不会造成故障，
     * 但会让「配置里有什么」变得难以解释 —— 排查问题时先花时间猜这些 key 是干嘛的。
     *
     * 全部用户的配置文件都还没初始化时返回 null，不占一行自检结果。
     */
    private fun configConsistency(): CheckItem? {
        val existing = Prefs.hookKeySet()
        if (existing.isEmpty()) return null

        val known = Features.ALL.map { it.key }.toMutableSet().apply {
            add(Prefs.MAX_ACCOUNTS)
            add(Prefs.DIAG_TOKEN)
        }
        val unknown = (existing - known).sorted()

        return CheckItem(
            owner = "配置",
            member = "key 一致性（共 ${existing.size} 项）",
            ok = unknown.isEmpty(),
            suggestions = unknown.take(8)
        )
    }

    /**
     * Hook 冲突检测：判断目标方法是否已被其他模块占用。
     *
     * 两个模块 hook 同一方法时的表现极难排查 —— 可能互相覆盖导致其中一个静默失效，
     * 也可能因为优先级不同出现「有时生效有时不生效」。点名列出来，
     * 至少让用户知道该去关哪个模块，而不是对着「功能没反应」干瞪眼。
     *
     * 没有检测到冲突时返回 null，不占一行自检结果。
     */
    private fun conflictCheck(): CheckItem? {
        val list = ConflictWatch.list()
        if (list.isEmpty()) return null

        XLog.w("[诊断] 检测到 ${list.size} 个 Hook 点已被其他模块占用：${list.joinToString(", ")}")
        return CheckItem(
            owner = "冲突检测",
            member = "被其他模块占用的 Hook 点（${list.size} 个）",
            ok = false,
            suggestions = list.take(8)
        )
    }

    /**
     * 挂载失败检查：哪些组的安装过程抛了异常。
     *
     * 和「功能不可用」要分开看：不可用是目标类压根不存在（老版本的正常现象），
     * 失败是类在、但挂载时炸了 —— 通常意味着签名变了或与其他模块冲突，
     * 属于真正需要修的问题。
     */
    private fun installFailureCheck(): CheckItem? {
        val failed = HookStatus.failedGroups()
        if (failed.isEmpty()) return null

        XLog.w("[诊断] 挂载失败的组：${failed.joinToString("、")}")
        return CheckItem(
            owner = "挂载",
            member = "${failed.size} 组挂载失败（异常，非「类不存在」）",
            ok = false,
            suggestions = failed
        )
    }

    /**
     * 功能可用性：哪些功能在当前客户端上没有找到 Hook 点。
     *
     * 这些功能**开关打开也不会生效**。不单独列出来的话，用户只会看到
     * 「点了没反应」，而原因（类不存在）埋在二十多行挂载日志中间。
     *
     * 全部可用时返回 null，不占一行结果。
     */
    private fun availabilityCheck(): CheckItem? {
        val keys = HookStatus.unavailableKeys()
        if (keys.isEmpty()) return null

        val titles = keys.map { key -> Features.find(key)?.title ?: key }
        XLog.w("[诊断] 当前客户端不支持的功能：${titles.joinToString("、")}")

        return CheckItem(
            owner = "可用性",
            member = "当前客户端不支持的功能（${keys.size} 项，开了也不会生效）",
            ok = false,
            suggestions = titles.take(8)
        )
    }

    /**
     * 特征兜底命中检查（v6.0.0）。
     *
     * 正常情况下应该一条都没有 —— 出现了就说明官方改了那个方法的名字，
     * 模块是靠「返回类型 + 名字特征」侥幸接住的。这种情况必须让用户看到：
     * 这次接住了，下次改动幅度再大一点就会彻底失效。
     */
    private fun fuzzyMatchCheck(): CheckItem? {
        val hits = HookFinder.fuzzyMatched()
        if (hits.isEmpty()) return null

        XLog.w("[诊断] 以下 Hook 点靠特征兜底命中（官方改过名）：${hits.joinToString("、")}")
        return CheckItem(
            owner = "适配",
            member = "${hits.size} 个 Hook 点靠特征兜底命中（方法名已变）",
            ok = false,
            suggestions = hits.take(8)
        )
    }

    /**
     * 框架兼容性检查。
     *
     * 这两条信息基本能解释「为什么开关点了没反应」：
     *
     * - **跨进程配置能不能读到**：读不到时所有开关都走默认值（全关），
     *   表现就是模块完全没反应。LSPatch 等免 root 方案常见。
     * - **Zygote 注入回调有没有被调用**：不是必须的（本模块不依赖它），
     *   但能帮助判断当前跑在哪种注入方式下。
     */
    private fun frameworkCheck(): CheckItem {
        val prefsOk = Prefs.isPrefsAvailable
        val zygoteOk = Prefs.isZygoteInitialized

        return CheckItem(
            owner = "框架",
            member = "配置读取 " + (if (prefsOk) "正常" else "不可用") +
                "；Zygote 注入 " + (if (zygoteOk) "已调用" else "未调用"),
            ok = prefsOk,
            suggestions = if (prefsOk) {
                emptyList()
            } else {
                listOf("配置不可读时所有功能都会保持关闭 —— LSPatch 等免 root 方案常见")
            }
        )
    }

    /**
     * 方法名对不上时，在同一个类里找名字相近的方法作为候选。
     *
     * 做法是取「方法名的词干」前 8 个字符做包含匹配：
     * `getMaxAccountCount` -> `maxaccountcount` -> `maxaccou`，
     * 这样官方把它改成 `getMaxAccountsCount` 时仍然能命中。
     */
    private fun suggestCandidates(cls: Class<*>, methodName: String): List<String> {
        val lower = methodName.lowercase()
        val stem = lower
            .removePrefix("get")
            .removePrefix("set")
            .removePrefix("is")
            .removePrefix("has")
        val probe = (if (stem.length >= 4) stem else lower).take(8)
        if (probe.length < 4) return emptyList()

        return try {
            cls.declaredMethods.asSequence()
                .map { it.name }
                .filter { it.lowercase().contains(probe) }
                .distinct()
                .sorted()
                .take(6)
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun check(classLoader: ClassLoader, target: Target): List<CheckItem> {
        val simpleName = target.className.substringAfterLast('.')

        val cls = try {
            XposedHelpers.findClassIfExists(target.className, classLoader)
        } catch (t: Throwable) {
            null
        }

        if (cls == null) {
            XLog.w("[诊断] 类不存在: ${target.className}")
            return listOf(CheckItem(simpleName, MISSING_CLASS, false))
        }

        val out = ArrayList<CheckItem>()
        val logParts = ArrayList<String>()

        for (methodName in target.methods) {
            val overloads = try {
                cls.declaredMethods.filter { it.name == methodName }
            } catch (t: Throwable) {
                emptyList()
            }

            if (overloads.isEmpty()) {
                out.add(
                    CheckItem(
                        owner = simpleName,
                        member = "$methodName()",
                        ok = false,
                        suggestions = suggestCandidates(cls, methodName)
                    )
                )
                logParts.add("$methodName -> 缺失")
                continue
            }

            for (m in overloads) {
                val params = m.parameterTypes.joinToString(",") { it.simpleName }
                out.add(CheckItem(simpleName, "$methodName($params)", true))
                logParts.add("$methodName($params) -> ${m.returnType.simpleName}")
            }
        }

        XLog.result("诊断", "$simpleName: ${logParts.joinToString(" | ")}")
        return out
    }

    private const val MISSING_CLASS = "类缺失"
}
