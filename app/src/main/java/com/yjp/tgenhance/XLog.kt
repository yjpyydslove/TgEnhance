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

    @Volatile
    var enabled: Boolean = true

    fun i(msg: String) {
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
        if (!enabled) return
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
        }
    }
}
