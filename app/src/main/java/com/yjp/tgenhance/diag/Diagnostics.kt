package com.yjp.tgenhance.diag

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.core.Features
import com.yjp.tgenhance.core.OsCompat
import com.yjp.tgenhance.hooks.ClientProfileDetector
import com.yjp.tgenhance.hooks.ConflictWatch
import com.yjp.tgenhance.hooks.HookCatalog
import com.yjp.tgenhance.hooks.HookFinder
import com.yjp.tgenhance.hooks.HookRegistry
import com.yjp.tgenhance.hooks.HookStatus
import com.yjp.tgenhance.hooks.HookTarget
import com.yjp.tgenhance.hooks.StealthSelfTest
import com.yjp.tgenhance.hooks.TargetOwner
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

    /**
     * 注册表与统计表的一致性（v N2.0）。
     *
     * [HookRegistry] 声明**挂载点**，[HookCatalog] 声明**运行期计数点**，
     * 两者靠 key 对应。对不上时不会有任何报错，只表现为莫名其妙：
     * 某一行在「运行状态」里永远显示 0，或者某次计数显示成原始的 `privacy.foo`。
     *
     * 这条检查直接比较两张表的真实内容（不是文本匹配），所以既不会漏也不会误报。
     *
     * 两类豁免：
     *  - `stealth.*` —— 隐身的拦截面由 [StealthSelfTest] 独立覆盖，
     *    它们不是通过 [HookRegistry] 挂载的；
     *  - `internal.callbackError` —— 由日志模块自己打点，不对应任何挂载点。
     */
    private fun registryConsistency(): CheckItem? {
        val declared = HookCatalog.statKeys.toSet()
        val covered = HookRegistry.coveredStatKeys.toSet()
        val exempt = declared.filter { it.startsWith("stealth.") }.toSet() +
            setOf("internal.callbackError")

        val missing = (declared - covered - exempt).sorted()
        val extra = (covered - declared).sorted()
        if (missing.isEmpty() && extra.isEmpty()) return null

        val problems = ArrayList<String>()
        if (missing.isNotEmpty()) problems += "有计数点没有挂载点声明：${missing.joinToString("、")}"
        if (extra.isNotEmpty()) problems += "有挂载点指向不存在的计数点：${extra.joinToString("、")}"

        XLog.w("[诊断] 注册表与统计表不一致：${problems.joinToString("；")}")
        return CheckItem(
            owner = "注册表",
            member = "挂载点与计数点不一致（${missing.size + extra.size} 处）",
            ok = false,
            suggestions = problems
        )
    }

    /**
     * 待探测清单 —— **从 [HookRegistry] 派生**（v N2.0）。
     *
     * 在此之前这里手写了一份清单，与实际挂载点各维护一份。结果是反复出问题：
     *
     *  - **N1.7**：`updateTimerProc`、`isTabletInternal` 两个挂载点从没进过这份
     *    清单 —— 它们改名时功能会静默失效，而自检照样全绿；
     *  - **N1.9**：这份清单只看「方法名在不在」，而 `HookFinder` 还要看返回类型
     *    与参数个数 —— 官方改签名时，自检说「命中」而功能说「不可用」，两边矛盾。
     *
     * 两轮都是打补丁。现在改成从注册表读：**注册表里有什么就查什么**，
     * 「加了挂载点却漏了自检」在结构上不可能再发生。
     *
     * 类名也不再写死在这里 —— 主 Activity / StoriesController / 设置页
     * 三类的实际路径由 [TargetOwner] 标记、运行期交给 `ClientProfileDetector`
     * 解析（fork 会把它们搬到别处）。
     */
    private val TARGETS: List<HookTarget> get() = HookRegistry.selfCheckTargets

    @Volatile
    private var lastReport: List<CheckItem> = emptyList()

    /**
     * 最近一次自检报告覆盖到的不可用项（v N1.8）。
     *
     * 用来和当前的 [HookStatus] 求差，找出「报告生成之后才出现的」那些 ——
     * 见 [newlyUnavailable]。
     */
    @Volatile
    private var reportedUnavailable: Set<String> = emptySet()

    /** 最近一次自检结果；未执行过时为空列表。 */
    fun report(): List<CheckItem> = lastReport

    /**
     * 自检报告生成**之后**才出现的不可用项（v N1.8）。
     *
     * 有些失败只有运行期才知道 —— 典型的是注入 Telegram 设置页时的锚点判据：
     * 要等用户真的打开设置页、列表被填好之后才能判断。而自检报告是挂载期
     * 算一次就缓存住的，这类问题**永远进不去**报告。
     *
     * 于是这里拿报告覆盖到的范围求差，把之后新增的单独报出来，
     * 让「这个功能为什么没出现」有地方可查，而不是只剩日志里的一行。
     *
     * 返回的是配置 key（面向程序），[DiagBridge] 回传时会翻成功能标题。
     */
    fun newlyUnavailable(): List<String> = try {
        HookStatus.unavailableKeys().filterNot { reportedUnavailable.contains(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    fun run(classLoader: ClassLoader) {
        XLog.section("诊断报告开始")
        val items = ArrayList<CheckItem>()

        // 客户端信息放最前面：它是解读后面所有结果的上下文
        val profile = ClientProfileDetector.current()
        if (profile != null) {
            val outdated = profile.isBelowSupportedVersion()
            items += CheckItem(
                owner = "客户端",
                member = profile.summary() + " · " + environmentSummary(),
                ok = profile.launchActivity != null &&
                    profile.storiesController != null && !outdated,
                suggestions = ArrayList<String>().apply {
                    if (outdated) add("客户端版本偏旧：部分功能在本版上不可用")
                    if (profile.launchActivity == null) add("未识别到主 Activity：配置热更新退回系统 Activity")
                    if (profile.storiesController == null) add("未识别到 StoriesController：隐藏 Stories 不可用")
                    // 设置页探测结果：v N1.4 起真的会往设置页里插条目了，
                    // 所以「找不到设置页类」不再是无关紧要的信息 ——
                    // 它等于「设置页入口在这台设备上不会出现」
                    if (profile.settingsFragment == null) {
                        add("未识别到设置页类：Telegram 设置页入口不可用（其余功能不受影响）")
                    }
                }
            )
            // 主 Activity / StoriesController / 设置页这三类的自检**不在这里做**
            // （v N2.0）：它们已经作为 HookTarget 进了 [HookRegistry]，
            // 由下面的 TARGETS 循环统一覆盖。此前在这里对每个解析出的类
            // 各调一次 check()，等于同一件事有两个入口，容易改一处漏一处。
        }

        for (target in TARGETS) {
            items += check(classLoader, target)
        }
        configConsistency()?.let { items += it }
        registryConsistency()?.let { items += it }
        conflictCheck()?.let { items += it }
        availabilityCheck()?.let { items += it }
        installFailureCheck()?.let { items += it }
        internalErrorCheck()?.let { items += it }
        fuzzyMatchCheck()?.let { items += it }
        signatureMismatchCheck()?.let { items += it }
        items += frameworkCheck()
        // 反检测自检放最后：它验的是「前面所有拦截都装好之后」的实际效果，
        // 必须等 StealthHooks.install 跑完才有意义
        stealthSelfTest(classLoader)?.let { items += it }
        // 有问题的项排前面：自检结果动辄十几行，没人会逐行读完，
        // 把「需要处理的」顶到最上面，比按类别整齐排列更有用
        lastReport = items.sortedBy { if (it.ok) 1 else 0 }
        // 记下这份报告已经覆盖到的不可用项。之后运行期才发现的（例如设置页
        // 入口的锚点判据，要等用户打开 Telegram 设置页才知道）不在其中，
        // 由 [newlyUnavailable] 单独补报。
        reportedUnavailable = HookStatus.unavailableKeys().toSet()

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
     * 设备与系统环境。
     *
     * 反馈问题时「什么手机、什么系统」几乎必问 —— 很多 Hook 失效其实是
     * 特定 ROM 的类加载差异或系统版本行为变化，光看客户端版本判断不出来。
     * 直接写进诊断报告，省一轮来回问。
     */
    /**
     * 设备与系统环境摘要（v7.8.0 起）。
     *
     * v N1.1 起改用 [OsCompat.summary]：除版本号外，把几个**会影响本模块行为**
     * 的系统分支判定一起写出来（接收器导出标志、强制全屏、隐式意图限制）。
     * 这几个分支此前只存在于代码里，用户反馈问题时没法说明自己落在哪一支 ——
     * 现在报告里直接带着，一眼就能对上。
     */
    private fun environmentSummary(): String = try {
        OsCompat.summary() +
            " · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    } catch (t: Throwable) {
        "环境未知"
    }

    /**
     * 内部异常检查（v6.4.0）。
     *
     * 回调异常已被 guard 兜住、不会影响 Telegram，但「兜住了」不等于「没问题」——
     * 它说明模块内部有逻辑在这台设备 / 这个版本上跑不通。
     * 拎出来单独成项，免得异常一直在发生、日志一直被刷，却没人知道。
     */
    private fun internalErrorCheck(): CheckItem? {
        val count = HookStats.countOf("internal.callbackError")
        if (count <= 0) return null

        XLog.w("[诊断] 捕获到 $count 次回调异常")
        return CheckItem(
            owner = "稳定性",
            member = "捕获到 $count 次回调异常（已忽略，未影响 Telegram）",
            ok = false,
            suggestions = listOf("请反馈诊断报告，便于定位是哪台设备 / 哪个版本的问题")
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
     * 签名不符检查（v N1.9）。
     *
     * 方法名还在、但没有一个签名满足 Hook 的过滤条件 —— 官方改签名时落在这里。
     * 这类失败此前表现为**两套标准互相打架**：自检按名字判定说「命中」，
     * 而 [HookFinder.match] 按签名判定找不到，功能直接不可用
     * （调用点会把它标进「当前客户端不支持」）。用户同时看到两边矛盾的说法。
     *
     * 现在自检改用 HookFinder 的实际判定（逐条会标红），这里再汇总一条，
     * 让「有几个点是签名问题」一眼可见。
     *
     * 注意它与「靠特征兜底命中」是两回事：**那个还能工作，这个已经不能了**。
     * 所以两者分开报，不合并。
     */
    private fun signatureMismatchCheck(): CheckItem? {
        val hits = HookFinder.signatureMismatches()
        if (hits.isEmpty()) return null

        XLog.w("[诊断] 以下 Hook 点方法名还在、但签名不符（对应功能已失效）：${hits.joinToString("、")}")
        return CheckItem(
            owner = "适配",
            member = "${hits.size} 个 Hook 点方法名还在但签名不符（该功能已失效）",
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
     * 反检测自检（v N1.2）。
     *
     * 用检测方的手法探测自己，把「挡没挡住」变成可读的事实。
     * 在此之前，「隐藏模块痕迹」是用户只能选择相信的开关。
     *
     * 开关没开时返回 null 不占行 —— 那种情况下所有探测都会「没挡住」，
     * 列出来只会误导；用户自己知道开关是关的。
     */
    private fun stealthSelfTest(classLoader: ClassLoader): CheckItem? {
        if (!Prefs.hideXposed) return null

        val probes = StealthSelfTest.runAndStore(classLoader)
        if (probes.isEmpty()) return null

        val leaked = probes.filter { !it.blocked }
        return CheckItem(
            owner = "反检测",
            member = "自检：${probes.size - leaked.size}/${probes.size} 项探测已挡住",
            ok = leaked.isEmpty(),
            suggestions = leaked.map { "${it.name}：${it.detail}" }
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

    /**
     * 解析一个 Hook 点对应的类（v N2.0）。
     *
     * 类名有两种来源：写死在注册表里的（[TargetOwner.FIXED]），
     * 以及运行期从客户端档案解析的（主 Activity / StoriesController /
     * 设置页 —— fork 会把它们搬到别处）。此前这件事散在 run() 里、
     * 对每个 profile 字段各写一遍，现在收在一处。
     */
    private fun resolveClass(target: HookTarget, classLoader: ClassLoader): Class<*>? = try {
        when (target.owner) {
            TargetOwner.FIXED ->
                XposedHelpers.findClassIfExists(target.className, classLoader)
            TargetOwner.LAUNCH_ACTIVITY ->
                ClientProfileDetector.launchActivityClass(classLoader)
            TargetOwner.STORIES_CONTROLLER ->
                ClientProfileDetector.storiesControllerClass(classLoader)
            TargetOwner.SETTINGS_FRAGMENT ->
                ClientProfileDetector.settingsFragmentClass(classLoader)
        }
    } catch (t: Throwable) {
        null
    }

    private fun check(classLoader: ClassLoader, target: HookTarget): List<CheckItem> {
        val simpleName = target.shortName

        val cls = resolveClass(target, classLoader)

        if (cls == null) {
            // 类缺失对某些客户端是正常的（老版本没有 Stories 之类），
            // 但至少要说清是哪个功能的目标类，而不是只丢一个类简名
            XLog.w("[诊断] 类不存在: $simpleName（${target.label}）")
            return listOf(
                CheckItem(
                    owner = simpleName,
                    member = MISSING_CLASS,
                    ok = false,
                    suggestions = listOf("目标类缺失：${target.label}")
                )
            )
        }

        val out = ArrayList<CheckItem>()
        val logParts = ArrayList<String>()

        for (methodName in target.names) {
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

            // 精确名仍在：说明官方没动这个方法名，不需要额外提示。
            //
            // v N1.3 修正：这里原本读 HookFinder.isFuzzyMatched(cls, methodName)
            // 来决定要不要标「特征兜底命中」，但那一问在本分支里**恒为 false** ——
            // 精确命中时 HookFinder 根本不会记兜底账；而精确落空时前面已经
            // continue 走了缺失分支，压根到不了这里。
            // 也就是说那行标注永远不显示，是条死逻辑。删掉，缺口由
            // fuzzyMatchCheck()（整体兜底清单）负责，那里才是准确的位置。
            //
            // v N1.9 修正：名字在**不等于** Hook 能找到它 —— HookFinder.match
            // 还额外要求返回类型与参数个数满足条件。官方改签名时问题正好落在
            // 这条缝里：自检报「命中」，功能却是不可用的，用户看到两边矛盾。
            // 所以这里要问一次 HookFinder 的实际判定结果。
            val mismatch = HookFinder.isSignatureMismatch(cls, methodName)

            for (m in overloads) {
                val params = m.parameterTypes.joinToString(",") { it.simpleName }
                out.add(
                    CheckItem(
                        owner = simpleName,
                        member = "$methodName($params)",
                        ok = !mismatch,
                        suggestions = if (mismatch) {
                            listOf(
                                "方法名还在，但没有一个签名满足 Hook 的过滤条件" +
                                    "（返回类型 / 参数个数）—— 该功能已失效"
                            )
                        } else {
                            emptyList()
                        }
                    )
                )
                logParts.add(
                    "$methodName($params) -> ${m.returnType.simpleName}" +
                        if (mismatch) "（签名不符，Hook 找不到）" else ""
                )
            }
        }

        // 单个类的重载可能很多（MessagesController 上就有几十个），
        // 全打出来会把日志页刷满，这里截断并标注省略了多少条
        val shown = logParts.take(MAX_PARTS_PER_CLASS)
        val suffix = if (logParts.size > shown.size) " …（另有 ${logParts.size - shown.size} 条未打印）" else ""
        XLog.result("诊断", "$simpleName: ${shown.joinToString(" | ")}$suffix")
        return out
    }

    private const val MISSING_CLASS = "类缺失"

    /** 单个类最多输出多少条方法签名，超出部分只记数量。 */
    private const val MAX_PARTS_PER_CLASS = 8
}
