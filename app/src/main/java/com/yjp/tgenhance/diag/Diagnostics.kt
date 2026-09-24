package com.yjp.tgenhance.diag

import com.yjp.tgenhance.XLog
import de.robv.android.xposed.XposedHelpers

/**
 * 诊断报告。
 *
 * 目的：Hook 点强依赖 Telegram 的类名/方法签名，而这些会随版本变动或混淆策略调整
 * 而失效。为了让「适配新版本」这件事不依赖反复试错，模块启动时会主动探测所有
 * 关键类与方法是否存在，并把结果打到 XposedBridge 日志。
 *
 * 用户在 LSPosed 管理器的「日志」页面能直接看到这份报告，把它贴出来即可精确定位
 * 需要修正的 Hook 点。
 */
object Diagnostics {

    private data class Target(val className: String, val methods: List<String>)

    private val TARGETS = listOf(
        Target(
            "org.telegram.messenger.UserConfig",
            listOf("getMaxAccountCount", "getInstance", "getActivatedAccountsCount", "hasPremiumOnAccounts")
        ),
        Target(
            "org.telegram.messenger.AccountInstance",
            listOf("getInstance")
        ),
        Target(
            "org.telegram.messenger.MessagesController",
            listOf("deleteMessages", "sendTyping")
        ),
        Target(
            "org.telegram.messenger.AndroidUtilities",
            listOf("getTypeface")
        ),
        Target(
            "org.telegram.tgnet.ConnectionsManager",
            listOf("checkProxy")
        ),
        Target(
            "org.telegram.ui.Stories.StoriesController",
            listOf("hasStories", "hasUnreadStories")
        ),
    )

    fun run(classLoader: ClassLoader) {
        XLog.section("诊断报告开始")
        for (target in TARGETS) {
            reportTarget(classLoader, target)
        }
        XLog.section("诊断报告结束")
    }

    private fun reportTarget(classLoader: ClassLoader, target: Target) {
        val cls = try {
            XposedHelpers.findClassIfExists(target.className, classLoader)
        } catch (t: Throwable) {
            null
        }

        if (cls == null) {
            XLog.w("[诊断] 类不存在: ${target.className}")
            return
        }

        val lines = mutableListOf<String>()
        for (methodName in target.methods) {
            val overloads = try {
                cls.declaredMethods.filter { it.name == methodName }
            } catch (t: Throwable) {
                emptyList()
            }

            if (overloads.isEmpty()) {
                lines.add("$methodName -> 缺失")
            } else {
                for (m in overloads) {
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    lines.add("$methodName($params) -> ${m.returnType.simpleName}")
                }
            }
        }

        XLog.result("诊断", "${target.className.substringAfterLast('.')}: ${lines.joinToString(" | ")}")
    }
}
