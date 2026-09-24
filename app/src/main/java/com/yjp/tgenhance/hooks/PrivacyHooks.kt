package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.safe
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
 *    （源码 11381 / 11385）。整体接管让其返回 false 即可，对方不会再看到输入提示。
 *
 * 2) 防撤回
 *    服务器下发的撤回是 `TL_updateDeleteMessages`，客户端在 `processUpdates` 中
 *    把消息 id 收进 `deletedMessages`（**key 固定为 0**，源码 18820），
 *    用户主动删除则用真实 dialogId 作为 key（源码 19346）。最终统一走
 *    `MessagesController.deleteMessages(...)` 并带上 `forAll = true`。
 *
 *    拦截判据：`randoms == null && encryptedChat == null && forAll == true`。
 *    副作用：自己发起的「为所有人删除」同样满足该判据，会被一并拦下 ——
 *    这是本功能的已知代价，设置界面会显式提示。
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
                override fun replaceHookedMethod(param: MethodHookParam): Any = false
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
                    val signature = param.args.size
                    if (!isServerSideRecall(param)) return
                    // 直接短路，不执行原删除逻辑
                    param.result = null
                    XLog.result("防撤回", "拦截一次服务器撤回 (参数个数=$signature)")
                }
            })
            XLog.result("隐私", "deleteMessages() 已接管：服务器撤回将被拦截")
        }
    }

    /**
     * 判断本次 deleteMessages 是否来自服务器撤回。
     *
     * 参数布局（源码 9307 / 9311 / 9319 三个重载）：
     *   [0] ArrayList messages
     *   [1] ArrayList randoms        <- 服务器撤回时为 null
     *   [2] EncryptedChat            <- 服务器撤回时为 null
     *   [3] long dialogId
     *   [4] int topicId / boolean forAll   <- 因重载而异
     *   ...  forAll 位置随重载变化，故按类型扫描
     */
    private fun isServerSideRecall(param: XC_MethodHook.MethodHookParam): Boolean {
        val args = param.args
        if (args.size < 6) return false

        // randoms 与 encryptedChat 必须都是 null —— 用户侧删除通常会带上它们
        if (args.getOrNull(1) != null) return false
        if (args.getOrNull(2) != null) return false

        // forAll 必须为 true（即「为所有人删除」= 撤回语义）
        val forAll = args.any { it is Boolean && it }
        if (!forAll) return false

        // 消息列表必须非空
        val messages = args.getOrNull(0)
        if (messages !is Collection<*> || messages.isEmpty()) return false

        return true
    }
}
