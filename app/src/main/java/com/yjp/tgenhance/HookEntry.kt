package com.yjp.tgenhance

import android.app.AndroidAppHelper
import com.yjp.tgenhance.diag.Diagnostics
import com.yjp.tgenhance.hooks.AccountHooks
import com.yjp.tgenhance.hooks.NetworkHooks
import com.yjp.tgenhance.hooks.PrivacyHooks
import com.yjp.tgenhance.hooks.ThemeHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
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

        if (Prefs.diagEnabled) {
            XLog.safe("Diagnostics") { Diagnostics.run(lpparam.classLoader) }
        }

        XLog.i("全部模块挂载流程结束")
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
