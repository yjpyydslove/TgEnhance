package com.yjp.tgenhance.diag

import com.yjp.tgenhance.XLog
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
        val ok: Boolean
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
            listOf("deleteMessages", "sendTyping", "completeReadTask")
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
            listOf("hasStories", "hasUnreadStories", "hasHiddenStories", "hasSelfStories")
        ),
        Target(
            "org.telegram.ui.LaunchActivity",
            listOf("onResume", "onPause")
        ),
    )

    @Volatile
    private var lastReport: List<CheckItem> = emptyList()

    /** 最近一次自检结果；未执行过时为空列表。 */
    fun report(): List<CheckItem> = lastReport

    fun run(classLoader: ClassLoader) {
        XLog.section("诊断报告开始")
        val items = ArrayList<CheckItem>()
        for (target in TARGETS) {
            items += check(classLoader, target)
        }
        lastReport = items

        val okCount = items.count { it.ok }
        val classMissing = items.count { !it.ok && it.member == MISSING_CLASS }
        XLog.result(
            "诊断",
            "自检完成：$okCount/${items.size} 项命中" +
                if (classMissing > 0) "，其中 $classMissing 项因类不存在而缺失" else ""
        )
        if (okCount < items.size) {
            XLog.w("[诊断] 未命中的项见上方逐条输出；设置界面的「运行状态」里也能直接看到")
        }
        XLog.section("诊断报告结束")
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
                out.add(CheckItem(simpleName, "$methodName()", false))
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
