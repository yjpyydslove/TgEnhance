package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 隐私与本地增强。
 *
 * ### v2.0.0 的结构变化：hook 常驻，开关实时读
 *
 * 上一版是「开关关着就不挂 hook」，副作用是改完设置必须重启 Telegram。
 * 现在改为**一律挂载**，在回调里读 `Prefs.*` 判断开关 —— 配合
 * [Prefs.reload]（hook `LaunchActivity.onResume` 时调用），改完设置切回
 * Telegram 前台即生效，不需要重启。
 *
 * 代价是功能关闭时 hook 仍在，但每个回调只是几次内存读，可以忽略。
 *
 * ### 三项能力
 *
 * 1) 隐藏「正在输入 / 录音中」
 *    `MessagesController.sendTyping(...)`（源码 11381 / 11385）。
 *    整体接管返回 false，对方不再看到输入提示。
 *
 * 2) 防撤回
 *    Telegram 的删除有两个来源，必须区分开，否则会误伤用户自己的删除操作：
 *
 *    - **服务器撤回**：走 `processUpdates` 链路；且因为 `TL_updateDeleteMessages`
 *      不带 peer 信息，消息 id 会被收进 `deletedMessages` 的 **key = 0** 槽位
 *      （MessagesController.java:18820）。
 *    - **用户主动删除**：从 UI 直接调用，key 是真实 dialogId（同文件 :19346）。
 *
 *    本实现用「调用栈 + dialogId」双重判据定位服务器撤回，
 *    判断不出的情况一律放行 —— **宁可漏拦，也不误伤用户自己删消息**。
 *
 * 3) 不上报已读回执（v2.0.0 新增）
 *    `MessagesController.completeReadTask(ReadTask)`（源码 14559）是已读位置
 *    **唯一的网络出口** —— `TL_messages_readHistory` / `channels_readHistory` /
 *    `readEncryptedHistory` / `readDiscussion` / `readSavedHistory` 五种请求全在这里发出。
 *
 *    拦截它即可做到「本地照常标记已读、但不告诉服务器」，
 *    对方永远看不到你的已读状态。
 *
 *    本地已读的写入发生在更早的 `markDialogAsRead`（约 14675 行）里，
 *    与这里解耦，所以拦截不会影响你自己的未读显示。
 */
object PrivacyHooks {

    private const val CLS_MESSAGES_CONTROLLER = "org.telegram.messenger.MessagesController"
    private const val CLS_LOCALE_CONTROLLER = "org.telegram.messenger.LocaleController"

    /** 缓存 LocaleController 的 Class，取本地化文本时要用。 */
    @Volatile
    private var localeControllerClass: Class<*>? = null

    fun install(classLoader: ClassLoader) {
        XLog.section("隐私与本地增强")
        XLog.i(
            "[隐私] 配置快照：防撤回=${Prefs.antiRecall}、" +
                "隐藏输入=${Prefs.hideTyping}、不上报已读=${Prefs.blockReadReceipt}、" +
                "隐藏在线=${Prefs.hideOnline}、隐藏对方在线=${Prefs.hidePeerOnline}" +
                "（运行期实时读取，改设置无需重启）"
        )

        hookHideTyping(classLoader)
        hookAntiRecall(classLoader)
        hookBlockReadReceipt(classLoader)
        hookHideOnline(classLoader)
        hookHidePeerOnline(classLoader)
    }

    // ------------------------------------------------------------------
    // 隐藏「正在输入」
    // ------------------------------------------------------------------

    private fun hookHideTyping(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_MESSAGES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[隐私] 未找到 $CLS_MESSAGES_CONTROLLER")
            return
        }

        // 精确名 + 特征兜底：官方把 sendTyping 改成别的名字时仍能命中
        val targets = HookFinder.match(
            cls,
            explicitNames = listOf("sendTyping"),
            returnType = Boolean::class.javaPrimitiveType,
            nameContains = "typing"
        )
        if (targets.isEmpty()) {
            XLog.w("[隐私] 未定位到 sendTyping，隐藏输入状态不可用")
            HookStatus.markUnavailable(Prefs.HIDE_TYPING)
            return
        }

        safe("隐藏输入状态") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        if (!Prefs.privacyEnabled || !Prefs.hideTyping) {
                            return invokeOriginal(param) ?: false
                        }
                        return try {
                            HookStats.hit("privacy.typing")
                            false
                        } catch (t: Throwable) {
                            XLog.e("[隐藏输入状态] 回调异常，本次放行", t)
                            invokeOriginal(param) ?: false
                        }
                    }
                })
            }
            XLog.result(
                "隐私",
                "sendTyping() 已接管 ${targets.size} 个重载：开启后不再发送输入/录音状态"
            )
        }
    }

    // ------------------------------------------------------------------
    // 防撤回
    // ------------------------------------------------------------------

    private fun hookAntiRecall(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_MESSAGES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[隐私] 未找到 $CLS_MESSAGES_CONTROLLER")
            return
        }

        // 精确名 + 特征兜底。注意不能按返回类型约束 —— 其多个重载返回值不同，
        // 这里只要求名字里含 deleteMessage，再在回调里自行判据
        val targets = HookFinder.match(
            cls,
            explicitNames = listOf("deleteMessages"),
            nameContains = "deletemessage"
        )
        if (targets.isEmpty()) {
            XLog.w("[隐私] 未定位到 deleteMessages，防撤回不可用")
            HookStatus.markUnavailable(Prefs.ANTI_RECALL)
            return
        }

        safe("防撤回") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guard("防撤回") {
                            // 只要 deleteMessages 被调用就计数，用于判断 hook 是否还挂在活跃路径上
                            HookStats.hit("privacy.deleteMessages")
                            if (!Prefs.privacyEnabled || !Prefs.antiRecall) return@guard
                            if (!isServerSideRecall(param)) return@guard
                            // 短路，不执行原删除逻辑
                            param.result = null
                            HookStats.hit("privacy.recall.blocked")
                            XLog.result("防撤回", "拦截一次服务器撤回 (参数个数=${param.args.size})")
                        }
                    }
                })
            }
            XLog.result(
                "隐私",
                "deleteMessages() 已接管 ${targets.size} 个重载：仅拦截服务器撤回，不影响自己删消息"
            )
        }
    }

    /**
     * 判定是否服务器撤回。详见类注释。
     *
     * ### v5.5.0 起：主要判据改为「按类型扫描」，不再依赖参数索引
     *
     * 原实现靠固定索引认参数（`args[1]` 是 randoms、`args[2]` 是 encryptedChat、
     * `args[3]` 是 dialogId）。官方一旦增删参数或调整顺序，这些索引会**静默错位** ——
     * 表现是「防撤回忽然不工作了」，或者更糟：误拦用户自己删的消息。
     *
     * 现在按类型推导：
     *   - 第一个集合 = 消息列表，其余集合（randoms）必须为空
     *   - 不允许出现集合/数值/布尔之外的复杂对象（EncryptedChat 之类）
     *   - 存在值为 true 的布尔（forAll，「为所有人删除」）
     *
     * 只有 dialogId 保留索引取值，且额外复核类型 —— 因为它和 threadId 都是 long，
     * 单看类型无法区分；而 `dialogId == 0` 这条判据一旦放宽成「任一 long 为 0」，
     * 就会把用户删自己的消息也拦下来。宁可少拦，不可误伤。
     */
    private fun isServerSideRecall(param: MethodHookParam): Boolean {
        val args = param.args
        if (args.size < 6) return false

        val collections = args.filterIsInstance<Collection<*>>()
        if (collections.isEmpty()) return false

        val messages = collections.first()
        if (messages.isEmpty()) return false

        // 除消息列表外的集合（randoms）若含内容，说明是用户主动删除
        if (collections.drop(1).any { it.isNotEmpty() }) return false

        // 出现额外的复杂对象参数（EncryptedChat 等）同样按用户删除处理
        if (args.any { it != null && !isSimpleValue(it) }) return false

        // forAll 必须为 true
        if (args.none { it is Boolean && it }) return false

        // 判据一：调用栈来自服务器更新处理链路（最精确）
        if (calledFromServerUpdateChain()) return true

        // 判据二：dialogId == 0，对应服务器撤回使用的 key=0 槽位
        val dialogId = args.getOrNull(3)
        return dialogId is Long && dialogId == 0L
    }

    /** 简单值：不参与「用户主动删除」的判据。 */
    private fun isSimpleValue(value: Any): Boolean =
        value is Collection<*> || value is Long || value is Int ||
            value is Boolean || value is String

    /**
     * 检测当前调用栈是否来自服务器更新处理链路。
     *
     * deleteMessages 调用频率很低（每条消息删除一次），抓栈开销可忽略。
     */
    private fun calledFromServerUpdateChain(): Boolean =
        try {
            Thread.currentThread().stackTrace.any { frame ->
                when (frame.methodName) {
                    "processUpdates", "processUpdateArray", "processUpdate" -> true
                    else -> false
                }
            }
        } catch (t: Throwable) {
            false
        }

    // ------------------------------------------------------------------
    // 不上报已读回执
    // ------------------------------------------------------------------

    private fun hookBlockReadReceipt(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_MESSAGES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[隐私] 未找到 $CLS_MESSAGES_CONTROLLER")
            return
        }

        safe("不上报已读回执") {
            HookInstaller.hookAllByName(cls, "completeReadTask", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    if (!Prefs.privacyEnabled || !Prefs.blockReadReceipt) return invokeOriginal(param)
                    return try {
                        HookStats.hit("privacy.readReceipt.blocked")
                        null
                    } catch (t: Throwable) {
                        XLog.e("[不上报已读] 回调异常，本次放行", t)
                        invokeOriginal(param)
                    }
                }
            })
            XLog.result("隐私", "completeReadTask() 已接管：开启后不上报已读位置")
        }
    }

    /**
     * 放行：手动执行被替换掉的原方法。
     *
     * 用 [XC_MethodReplacement] 时必须显式回退，否则「开关关闭」也会顺手把原逻辑吃掉。
     */
    private fun invokeOriginal(param: MethodHookParam): Any? =
        try {
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
        } catch (t: Throwable) {
            XLog.e("回退原方法失败 (${param.method.name}): ${t.message}")
            null
        }

    // ------------------------------------------------------------------
    // 隐藏在线状态
    // ------------------------------------------------------------------

    /** 字段名对不上时只提示一次，避免每次都打日志刷屏。 */
    @Volatile
    private var onlineFieldWarned = false

    /**
     * 隐藏在线状态（v2.4.0 新增）。
     *
     * `MessagesController.updateTimerProc()`（源码 10520）是状态上报的唯一入口，
     * 它按 `ignoreSetOnline` 决定发哪一种 `TL_account.updateStatus`：
     *
     * - `!ignoreSetOnline` → 发 `offline = false`，等于告诉服务器「我在线」
     * - 否则              → 发 `offline = true`
     *
     * 所以只要在它执行前把 `ignoreSetOnline` 顶成 true，就再也不会有人看到你在线，
     * 对方只会看到「最后上线」停留在很早以前。
     *
     * 相比拦网络请求，这种做法的好处是：不碰任何数据包、不需要 hook 高频的
     * `sendRequest`，也不会让 Telegram 自身逻辑出现状态错乱。
     */
    private fun hookHideOnline(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_MESSAGES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[隐私] 未找到 $CLS_MESSAGES_CONTROLLER")
            return
        }

        safe("隐藏在线状态") {
            HookInstaller.hookAllByName(cls, "updateTimerProc", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Prefs.privacyEnabled || !Prefs.hideOnline) return
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "ignoreSetOnline", true)
                        HookStats.hit("privacy.hideOnline")
                    } catch (t: Throwable) {
                        if (!onlineFieldWarned) {
                            onlineFieldWarned = true
                            XLog.e("[隐私] ignoreSetOnline 字段不可写，隐藏在线状态无效: ${t.message}")
                        }
                    }
                }
            })
            XLog.result("隐私", "updateTimerProc() 已接管：开启后不再上报在线状态")
        }
    }

    // ------------------------------------------------------------------
    // 隐藏对方的在线状态
    // ------------------------------------------------------------------

    /**
     * 隐藏对方的在线状态（v5.6.0 新增）。
     *
     * hook `LocaleController.formatUserStatus(...)`（源码 2888 / 2919 / 2923 三个重载）——
     * 它是「对方在线 / 最后上线于 …」这段文字的唯一生成处，返回 String，
     * 参数里的 `boolean[] isOnline` 是出参，界面靠它决定要不要画那个小绿点。
     *
     * 处理方式很克制：`isOnline[0]` 为 true 时改回 false，显示文本换成 Telegram 自己的
     * `Lately`（「最近上线」）。**不编造时间** —— 显示的内容仍然是 Telegram 给出的真实状态，
     * 只是不告诉你「对方此刻正在线」这一条动态信息。
     */
    private fun hookHidePeerOnline(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_LOCALE_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[隐私] 未找到 $CLS_LOCALE_CONTROLLER")
            return
        }
        localeControllerClass = cls

        val targets = HookFinder.findMethods(
            cls,
            returnType = String::class.java,
            namePrefix = "formatUserStatus"
        )
        if (targets.isEmpty()) {
            XLog.w("[隐私] 未定位到 formatUserStatus，隐藏对方在线状态不可用")
            HookStatus.markUnavailable(Prefs.HIDE_PEER_ONLINE)
            return
        }

        safe("隐藏对方在线状态") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("隐藏对方在线状态") {
                            if (!Prefs.privacyEnabled || !Prefs.hidePeerOnline) return@guard

                            val isOnline = param.args
                                .filterIsInstance<BooleanArray>()
                                .firstOrNull() ?: return@guard
                            if (!isOnline[0]) return@guard

                            isOnline[0] = false
                            latelyText()?.let { param.result = it }
                            HookStats.hit("privacy.peerOnline")
                        }
                    }
                })
            }
            XLog.result(
                "隐私",
                "formatUserStatus() 已接管 ${targets.size} 个重载：开启后对方不再显示「在线」"
            )
        }
    }

    /** 取「最近上线」的本地化文本；取不到时保留原文，不做无意义的兜底。 */
    private fun latelyText(): String? = try {
        val cls = localeControllerClass ?: return null
        XposedHelpers.callStaticMethod(cls, "getString", "Lately", 0) as? String
    } catch (t: Throwable) {
        null
    }
}
