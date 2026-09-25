package com.yjp.tgenhance.diag

import android.app.AndroidAppHelper
import android.content.Intent
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.core.Features
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把运行期诊断结果回传给模块设置界面（hook 端使用）。
 *
 * ### 为什么需要它
 *
 * 判断 Hook 是否真正生效，此前只能翻 LSPosed 日志 —— 要离开应用、进管理器、
 * 搜索关键字，普通用户根本不会去做。结果是「功能没生效」这件事没人发现。
 *
 * 现在把 [HookStats] 的计数快照通过显式广播送回设置界面，
 * 用户点开模块图标就能直接看到哪些 Hook 点真的被触发过。
 *
 * ### 为什么是广播
 *
 * - hook 端（Telegram 进程）与设置界面（模块进程）是两个不同 uid 的进程，
 *   无法直接共享文件：模块私有目录 Telegram 无权限，LSPosed 暴露的
 *   `XSharedPreferences` 又是只读的。
 * - 广播只要带 `setPackage(模块包名)` 就是显式投递，不依赖组件导出配置，
 *   也不需要多声明一个 ContentProvider。
 *
 * 安全上靠 [DiagProtocol.EXTRA_TOKEN] 过滤：令牌只存在于模块私有配置里，
 * 其他应用伪造不了，最多也就是被丢弃。
 */
object DiagBridge {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 发送一次诊断快照。任何失败都只记日志，不影响 Telegram 主流程。 */
    fun broadcast() {
        try {
            val token = Prefs.diagToken
            if (token.isEmpty()) {
                XLog.w("[诊断] 回传令牌尚未生成 —— 请先打开一次模块设置界面")
                return
            }
            val app = AndroidAppHelper.currentApplication() ?: return

            val intent = Intent(DiagProtocol.ACTION).apply {
                setPackage(Prefs.MODULE_PKG)
                putExtra(DiagProtocol.EXTRA_TOKEN, token)
                putExtra(DiagProtocol.EXTRA_PAYLOAD, buildPayload())
            }
            app.sendBroadcast(intent)
        } catch (t: Throwable) {
            XLog.e("[诊断] 回传失败", t)
        }
    }

    /**
     * 组装载荷：hook 触发计数 + 挂载期自检清单。
     *
     * 自检结果只在启动时算一次并缓存在 [Diagnostics] 里，之后每次广播都带上，
     * 所以设置界面不需要向 hook 端「发起请求」，也就不必在 Telegram 进程里
     * 再注册一个接收器。
     */
    private fun buildPayload(): String = buildString {
        append(HookStats.snapshot(timeFormat.format(Date())))

        append(DiagProtocol.SECTION_SELFCHECK).append('\n')
        val report = Diagnostics.report()
        if (report.isEmpty()) {
            append("# 未执行自检（诊断日志开关处于关闭状态）").append('\n')
        } else {
            for (item in report) {
                append(if (item.ok) DiagProtocol.MARK_OK else DiagProtocol.MARK_MISS)
                append(item.owner).append('#').append(item.member)
                if (item.suggestions.isNotEmpty()) {
                    append(DiagProtocol.SUGGESTION_SEP)
                    append(item.suggestions.joinToString(","))
                }
                append('\n')
            }
        }

        // 运行期才发现的不可用项（v N1.8）：自检报告是挂载期算一次就缓存的，
        // 而有些失败要等用户真的操作过才知道 —— 例如设置页入口的锚点判据，
        // 得等他打开 Telegram 设置页、列表填好之后才能判断。
        // 这些补在最后，格式与自检项一致，设置界面不用改就能显示出来。
        for (key in Diagnostics.newlyUnavailable()) {
            append(DiagProtocol.MARK_MISS)
            append("可用性").append('#')
            append("运行期发现：")
            append(Features.find(key)?.title ?: key)
            append(" 在当前客户端不可用")
            append('\n')
        }
    }
}
