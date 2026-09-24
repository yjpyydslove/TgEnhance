package com.yjp.tgenhance

import android.app.AndroidAppHelper
import android.os.Handler
import com.yjp.tgenhance.diag.Diagnostics
import com.yjp.tgenhance.diag.HookStats
import com.yjp.tgenhance.hooks.AccountHooks
import com.yjp.tgenhance.hooks.NetworkHooks
import com.yjp.tgenhance.hooks.PrivacyHooks
import com.yjp.tgenhance.hooks.ThemeHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口（由 assets/xposed_init 指定）。
 *
 * 每目标进程只加载一次：确认是 Telegram 系客户端后，先初始化配置读取，
 * 再按模块分组挂载 Hook。任何一组出错都不会影响其余分组 —— 每组都用
 * [XLog.safe] 独立包裹，避免一个失效的 Hook 点拖垮整个模块。
 */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XLog.i("initZygote 完成, modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 模块自身的设置界面进程不需要 Hook
        if (lpparam.packageName == Prefs.MODULE_PKG) return
        if (!isTelegramClient(lpparam)) return

        XLog.banner(lpparam.packageName, resolveVersion(lpparam))

        // 读取配置（只读 XSharedPreferences）
        Prefs.initForHook()

        XLog.safe("AccountHooks") { AccountHooks.install(lpparam.classLoader) }
        XLog.safe("ThemeHooks") { ThemeHooks.install(lpparam.classLoader) }
        XLog.safe("NetworkHooks") { NetworkHooks.install(lpparam.classLoader) }
        XLog.safe("PrivacyHooks") { PrivacyHooks.install(lpparam.classLoader) }
        XLog.safe("PrefsReload") { installPrefsReloadHook(lpparam.classLoader) }

        if (Prefs.diagEnabled) {
            XLog.safe("Diagnostics") { Diagnostics.run(lpparam.classLoader) }
        }

        // 挂载完成后安排一次触发统计输出：用于验证 Hook 是否**真的被调用**，
        // 而不仅仅是「挂上了」
        XLog.safe("HookStats") { scheduleStatsReport(lpparam.classLoader) }

        XLog.i("全部模块挂载流程结束")
    }

    /**
     * 挂载「配置热更新」钩子。
     *
     * v2.0.0 起所有 Hook 都常驻，开关改为在回调里实时读 `Prefs.*`，
     * 所以只要让 hook 端重新加载一次配置文件，多数设置就能**免重启**生效。
     *
     * 时机选 Telegram 主界面 `LaunchActivity.onResume`：用户从模块设置页切回
     * Telegram 时必然触发。Telegram 是单 Activity 架构，挂这一个就够。
     * 找不到该类时退回系统 `Activity.onResume`（[Prefs.reload] 内部有 1 秒节流）。
     */
    private fun installPrefsReloadHook(classLoader: ClassLoader) {
        val target = XposedHelpers.findClassIfExists("org.telegram.ui.LaunchActivity", classLoader)
            ?: XposedHelpers.findClassIfExists("android.app.Activity", classLoader)
        if (target == null) {
            XLog.w("[配置] 未找到可用的重载时机，改动设置后仍需重启 Telegram")
            return
        }

        XposedBridge.hookAllMethods(target, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                HookStats.hit("prefs.reload")
                Prefs.reload()
            }
        })
        XLog.result("配置", "已在 ${target.name}.onResume 挂载配置热更新：改设置切回即生效")
    }

    /**
     * 启动一段时间后输出 Hook 触发统计。
     *
     * 时机选在 `Application.onCreate` 之后延迟 [HookStats.REPORT_DELAY_MS]：
     * `handleLoadPackage` 阶段主线程 Looper 尚未就绪，无法直接 postDelayed；
     * 而 Application.onCreate 是 Telegram 进程内最早的稳定时机。
     */
    private fun scheduleStatsReport(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? android.app.Application ?: return
                        try {
                            Handler(app.mainLooper).postDelayed(
                                { HookStats.report() },
                                HookStats.REPORT_DELAY_MS
                            )
                            XLog.i("[统计] 已安排 ${HookStats.REPORT_DELAY_MS / 1000} 秒后输出 Hook 触发统计")
                        } catch (t: Throwable) {
                            XLog.e("[统计] 注册延迟任务失败", t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XLog.e("[统计] Hook Application.onCreate 失败", t)
        }
    }

    /**
     * 判定是否 Telegram 系客户端。
     *
     * 先用包名粗筛降低开销，再用「UserConfig 类是否存在」做精确认定 ——
     * 这样未知的第三方 fork 也能自动适配，不必维护包名白名单。
     */
    private fun isTelegramClient(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val pkg = lpparam.packageName.lowercase()
        val looksLikeTelegram = pkg.contains("telegram") ||
            pkg.contains("gram") ||
            pkg.contains("nekogram") ||
            pkg.contains("extera")
        if (!looksLikeTelegram) return false

        return try {
            XposedHelpers.findClassIfExists("org.telegram.messenger.UserConfig", lpparam.classLoader) != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun resolveVersion(lpparam: XC_LoadPackage.LoadPackageParam): String =
        try {
            AndroidAppHelper.currentApplication()
                ?.packageManager
                ?.getPackageInfo(lpparam.packageName, 0)
                ?.versionName
                ?: "未知"
        } catch (t: Throwable) {
            "未知"
        }
}
