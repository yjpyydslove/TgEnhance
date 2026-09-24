package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
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
 * 1) 隐藏「正在输入 / 录音中」状态
 *    `MessagesController.sendTyping(long dialogId, long threadMsgId, int action, [String emojicon,] int classGuid)`
 *    （源码 11381 / 11385）。整体接管返回 false，对方不再看到输入提示。
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
 */
object PrivacyHooks {

    private const val CLS_MESSAGES_CONTROLLER = "org.telegram.messenger.MessagesController"

    fun install(classLoader: ClassLoader) {
        if (!Prefs.privacyEnabled) {
            XLog.i("[隐私] 开关关闭，跳过")
            return
        }
        XLog.section("隐私与本地增强")

        if (Prefs.antiRecall) {
            hookAntiRecall(classLoader)
        } else {
            XLog.i("[隐私] 防撤回：未启用")
        }

        if (Prefs.hideTyping) {
            hookHideTyping(classLoader)
        } else {
            XLog.i("[隐私] 隐藏输入状态：未启用")
        }
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

        safe("隐藏输入状态") {
            XposedBridge.hookAllMethods(cls, "sendTyping", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    HookStats.hit("privacy.typing")
                    return false
                }
            })
            XLog.result("隐私", "sendTyping() 已接管：不再向对方发送输入/录音状态")
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

        safe("防撤回") {
            XposedBridge.hookAllMethods(cls, "deleteMessages", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 只要 deleteMessages 被调用就计数，用于判断 hook 是否还挂在活跃路径上
                    HookStats.hit("privacy.deleteMessages")
                    if (!isServerSideRecall(param)) return
                    // 短路，不执行原删除逻辑
                    param.result = null
                    HookStats.hit("privacy.recall.blocked")
                    XLog.result("防撤回", "拦截一次服务器撤回 (参数个数=${param.args.size})")
                }
            })
            XLog.result("隐私", "deleteMessages() 已接管：仅拦截服务器撤回，不影响自己删消息")
        }
    }

    /**
     * 判定是否服务器撤回。详见类注释。
     *
     * 参数布局（源码 9307 / 9311 / 9319 三个重载）：
     *   [0] ArrayList messages
     *   [1] ArrayList randoms        <- 服务器撤回时为 null
     *   [2] EncryptedChat            <- 服务器撤回时为 null
     *   [3] long dialogId            <- 各重载位置一致
     *   [4..] 其余参数随重载变化，故 forAll 按类型扫描
     */
    private fun isServerSideRecall(param: MethodHookParam): Boolean {
        val args = param.args
        if (args.size < 6) return false

        // randoms 与 encryptedChat 必须都是 null
        if (args.getOrNull(1) != null) return false
        if (args.getOrNull(2) != null) return false

        // forAll 必须为 true，即「为所有人删除」= 撤回语义
        if (args.none { it is Boolean && it }) return false

        // 消息列表必须非空
        val messages = args.getOrNull(0)
        if (messages !is Collection<*> || messages.isEmpty()) return false

        // 判据一：调用栈来自服务器更新处理链路（最精确）
        if (calledFromServerUpdateChain()) return true

        // 判据二：dialogId == 0，对应服务器撤回使用的 key=0 槽位
        val dialogId = args.getOrNull(3) as? Long
        return dialogId != null && dialogId == 0L
    }

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
}
