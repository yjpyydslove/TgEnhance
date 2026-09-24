package com.yjp.tgenhance

import android.app.AndroidAppHelper
import android.os.Handler
import android.os.SystemClock
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.diag.DiagBridge
import com.yjp.tgenhance.diag.Diagnostics
import com.yjp.tgenhance.diag.HookStats
import com.yjp.tgenhance.hooks.AccountHooks
import com.yjp.tgenhance.hooks.AdHooks
import com.yjp.tgenhance.hooks.ClientProfileDetector
import com.yjp.tgenhance.hooks.HookCatalog
import com.yjp.tgenhance.hooks.HookInstaller
import com.yjp.tgenhance.hooks.HookStatus
import com.yjp.tgenhance.hooks.NetworkHooks
import com.yjp.tgenhance.hooks.PrivacyHooks
import com.yjp.tgenhance.hooks.StealthHooks
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
        // 记录一下：部分免 root 方案（LSPatch 等）根本不走 Zygote 注入，
        // 这个回调永远不会被调用。它不是必须的，但要能在自检里看出来。
        Prefs.markZygoteInit()
        XLog.i("initZygote 完成, modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 模块自身的设置界面进程不需要 Hook
        if (lpparam.packageName == Prefs.MODULE_PKG) return
        if (!isTelegramClient(lpparam)) return

        // 先识别客户端：不同 fork 的类路径可能不同，后续挂载要靠它的解析结果
        val profile = ClientProfileDetector.detect(lpparam.classLoader, lpparam.packageName)
        XLog.banner(lpparam.packageName, profile.versionName)
        XLog.result("客户端", profile.summary())
        if (profile.launchActivity == null) {
            XLog.w("[客户端] 未识别到主 Activity：配置热更新与诊断回传将退回系统 Activity.onResume")
        }
        if (profile.storiesController == null) {
            XLog.w("[客户端] 未识别到 StoriesController：隐藏 Stories 在本客户端不可用")
        }

        // 读取配置（只读 XSharedPreferences）
        Prefs.initForHook()

        // 回调异常计入统计：出问题时能在「运行状态」里看到，
        // 而不是埋在日志的某一行里没人注意
        XLog.onCallbackError = { HookStats.hit("internal.callbackError") }

        // 详细日志开关：关掉后只留结论性输出，避免过程日志把报错挤掉
        XLog.verbose = Prefs.verboseLog

        // 预登记全部 hook 点：计数器默认是「首次触发才创建」，
        // 不预登记的话未触发的项不会出现在快照里，设置界面就无法区分
        // 「功能没生效」和「这个点根本没挂上」。
        // 清单来自 HookCatalog —— 新增 hook 点只需改那一处。
        HookStats.expect(*HookCatalog.statKeys.toTypedArray())

        // 各分组独立挂载：任何一组出错都不影响其余组。
        // 用 timed 而非 safe，把每一步耗时打到日志里 —— 这段代码在
        // handleLoadPackage 里同步执行，耗时直接算进 Telegram 的启动时间。
        // 返回值同时用来判断成败：install 正常返回 Unit，抛异常则 timed 返回 null。
        val startedAt = SystemClock.elapsedRealtime()
        val installResults = linkedMapOf(
            "多账号" to XLog.timed("AccountHooks") { AccountHooks.install(lpparam.classLoader) },
            "界面" to XLog.timed("ThemeHooks") { ThemeHooks.install(lpparam.classLoader) },
            "网络" to XLog.timed("NetworkHooks") { NetworkHooks.install(lpparam.classLoader) },
            "隐私" to XLog.timed("PrivacyHooks") { PrivacyHooks.install(lpparam.classLoader) },
            "反检测" to XLog.timed("StealthHooks") { StealthHooks.install(lpparam.classLoader) },
            "广告屏蔽" to XLog.timed("AdHooks") { AdHooks.install(lpparam.classLoader) },
            "配置热更新" to XLog.timed("PrefsReload") { installPrefsReloadHook(lpparam.classLoader) },
        )

        val failed = installResults.filterValues { it == null }.keys
        if (failed.isEmpty()) {
            XLog.result("挂载", "全部 ${installResults.size} 组挂载成功")
        } else {
            failed.forEach { HookStatus.markGroupFailed(it) }
            XLog.e("[挂载] ${failed.size}/${installResults.size} 组失败：${failed.joinToString("、")}")
            XLog.e("[挂载] 多数情况是 Telegram 版本变动或作用域未勾选，请连同版本号一起反馈")
        }

        var diagCost = 0L
        if (Prefs.diagEnabled) {
            val diagStart = SystemClock.elapsedRealtime()
            XLog.timed("Diagnostics") { Diagnostics.run(lpparam.classLoader) }
            diagCost = SystemClock.elapsedRealtime() - diagStart
        } else {
            XLog.i("[诊断] 开关关闭，跳过自检")
        }

        // 挂载完成后安排一次触发统计输出：用于验证 Hook 是否**真的被调用**，
        // 而不仅仅是「挂上了」
        XLog.timed("HookStats") { scheduleStatsReport(lpparam.classLoader) }

        val total = SystemClock.elapsedRealtime() - startedAt
        XLog.result("性能", "挂载总耗时 ${total}ms（其中自检 ${diagCost}ms）")
        XLog.i("全部模块挂载流程结束")
    }

    /**
     * 挂载「配置热更新 + 诊断回传」钩子。
     *
     * v2.0.0 起所有 Hook 都常驻，开关改为在回调里实时读 `Prefs.*`，
     * 所以只要让 hook 端重新加载一次配置文件，多数设置就能**免重启**生效。
     *
     * 时机选 Telegram 主界面 `LaunchActivity.onResume`：用户从模块设置页切回
     * Telegram 时必然触发。Telegram 是单 Activity 架构，挂这一个就够。
     * 找不到该类时退回系统 `Activity.onResume`（[Prefs.reload] 内部有 1 秒节流）。
     *
     * 同时在这里做诊断回传（v2.1.0）：
     *  - `onPause`：用户切走去设置界面，此刻发一次，另延迟 2 秒补发一次
     *    （贴住「设置界面注册完接收器」的时间点，否则首屏看不到数据）。
     *  - `onResume`：用户切回 Telegram，顺手刷新一次快照。
     */
    private fun installPrefsReloadHook(classLoader: ClassLoader) {
        // 主 Activity 走候选路径解析：第三方 fork 可能改掉 LaunchActivity 的位置
        val target = ClientProfileDetector.launchActivityClass(classLoader)
            ?: XposedHelpers.findClassIfExists("android.app.Activity", classLoader)
        if (target == null) {
            XLog.w("[配置] 未找到可用的重载时机，改动设置后仍需重启 Telegram")
            return
        }

        HookInstaller.hookAllByName(target, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                guard("配置热更新") {
                    HookStats.hit("prefs.reload")
                    Prefs.reload()
                    DiagBridge.broadcast()
                }
            }
        })

        HookInstaller.hookAllByName(target, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                guard("诊断回传") {
                    DiagBridge.broadcast()
                    // 补发一次：此刻模块设置界面可能刚启动、接收器还没注册好
                    scheduleDelayedBroadcast(param.thisObject)
                }
            }
        })

        XLog.result("配置", "已在 ${target.name}.onResume/onPause 挂载热更新与诊断回传")
    }

    /** 延迟补发一次诊断快照。 */
    private fun scheduleDelayedBroadcast(activity: Any?) {
        try {
            val ctx = activity as? android.content.Context ?: return
            Handler(ctx.mainLooper).postDelayed(
                { DiagBridge.broadcast() },
                DIAG_REBROADCAST_DELAY_MS
            )
        } catch (t: Throwable) {
            XLog.e("[诊断] 安排补发失败", t)
        }
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
     * v2.5.0 起**只靠类存在性判断，不再依赖包名**。
     *
     * 原因：第三方 fork 常常改包名（`com.example.tgclient` 之类），
     * 早先那套「包名里得有 telegram/gram」的粗筛会把它们全部漏掉 ——
     * 而漏掉的后果是用户看到「模块没反应」，却完全不知道是被自己的包名筛掉的。
     *
     * 开销可以忽略：LSPosed 只为「作用域内」的应用调用本方法，
     * 而且 `findClassIfExists` 就是一次类加载尝试。
     */
    private fun isTelegramClient(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (lpparam.packageName == Prefs.MODULE_PKG) return false
        return try {
            XposedHelpers.findClassIfExists(
                "org.telegram.messenger.UserConfig",
                lpparam.classLoader
            ) != null
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        /**
         * `onPause` 之后延迟补发诊断快照的间隔。
         *
         * 用户从 Telegram 切到模块设置界面时，设置界面刚走完 onCreate 还没注册
         * 广播接收器，立即发出的那条会被丢掉；延迟一点补发，首屏就能看到数据。
         */
        const val DIAG_REBROADCAST_DELAY_MS = 2_000L
    }
}
