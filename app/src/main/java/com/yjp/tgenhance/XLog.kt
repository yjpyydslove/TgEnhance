package com.yjp.tgenhance

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志出口。
 *
 * 诊断信息会同时写入：
 *  1. logcat（TAG = TgEnhance）
 *  2. XposedBridge 日志 —— 这是关键：LSPosed 管理器「日志」页面会收集它，
 *     用户可以在手机上直接查看并导出，无需 adb。
 *
 * 因此所有 hook 结果、失败原因都走这里，便于远程定位问题。
 */
object XLog {

    private const val TAG = "TgEnhance"
    private const val PREFIX = "TgEnhance"

    /**
     * 详细日志开关（v6.7.0）。
     *
     * 关掉后只保留 RESULT / WARN / ERROR 这三类结论性输出，
     * 启动期的配置快照、逐项挂载过程就不再刷屏 —— LSPosed 日志页一次
     * 只显示有限行数，被过程日志挤掉之后真正要紧的报错就看不见了。
     */
    @Volatile
    var verbose: Boolean = true

    /**
     * 回调异常的通知钩子（v6.3.0）。
     *
     * 由 [com.yjp.tgenhance.HookEntry] 在挂载时注册为「记一次内部异常计数」。
     * 用回调而不是直接调 HookStats，是为了避免 XLog ↔ HookStats 互相依赖 ——
     * HookStats 本身就用 XLog 输出，反过来再让 XLog 调它，依赖就成了环。
     */
    @Volatile
    var onCallbackError: ((String) -> Unit)? = null

    fun i(msg: String) {
        if (!verbose) return
        Log.i(TAG, msg)
        write(msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        write("WARN $msg")
    }

    fun e(msg: String) {
        Log.e(TAG, msg)
        write("ERROR $msg")
    }

    /** 带异常堆栈的错误。 */
    fun e(msg: String, t: Throwable) {
        Log.e(TAG, msg, t)
        write("ERROR $msg :: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** 结果型日志，用统一前缀方便在 LSPosed 日志里 grep。 */
    fun result(scope: String, detail: String) {
        write("RESULT [$scope] $detail")
    }

    fun banner(pkg: String, version: String) {
        write("==================== $PREFIX ====================")
        write("已挂载到 $pkg ($version)")
    }

    fun section(title: String) {
        write("---------- $title ----------")
    }

    private fun write(msg: String) {
        try {
            XposedBridge.log("[$PREFIX] $msg")
        } catch (t: Throwable) {
            // XposedBridge 不可用时（理论上不会）静默降级到 logcat
        }
    }

    /** 失败但不应中断流程的包装执行。 */
    inline fun <T> safe(scope: String, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            e("[$scope] 执行失败", t)
            null
        }

    /**
     * 带计时的 [safe]，用于挂载阶段。
     *
     * 模块在 Telegram 的 `handleLoadPackage` 里同步执行，这段耗时会直接加到
     * 应用启动时间上 —— 用户体感是「装了模块之后 TG 打开变慢了」。
     * 把每一步的耗时打出来，才能判断某个 Hook 是否值得重新实现。
     */
    inline fun <T> timed(scope: String, block: () -> T): T? =
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            val result = block()
            result("性能", "$scope 耗时 ${android.os.SystemClock.elapsedRealtime() - start}ms")
            result
        } catch (t: Throwable) {
            e("[$scope] 执行失败", t)
            null
        }

    /**
     * Hook 回调的统一兜底。
     *
     * Xposed 框架本身会捕获回调异常并继续执行原方法，但它的日志里看不出这是
     * 模块引入的问题。模块自己再包一层，是为了在 LSPosed 日志里留下带模块前缀的
     * 明确记录 —— 「宿主行为异常」这类问题最难排查的就是归因。
     *
     * 零开销：声明为 inline，高频 Hook 点（如 `getTypeface`）不会因为包装而变慢。
     */
    inline fun guard(scope: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            e("[$scope] 回调异常（已忽略，不影响 Telegram 自身逻辑）", t)
            // 让「回调抛过异常」变成可统计的事实，而不是埋在日志里
            try {
                onCallbackError?.invoke(scope)
            } catch (ignored: Throwable) {
            }
        }
    }
}
