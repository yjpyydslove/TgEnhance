package com.yjp.tgenhance.hooks

import android.content.pm.PackageManager
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 反检测（模块隐身）。
 *
 * 目的：让宿主应用看不出自己跑在 Xposed / LSPosed 上，也看不出本模块被装了。
 * 下面三种是检测方最常用的手法，逐一挡掉：
 *
 * 1. **按类名探测** —— `Class.forName("de.robv.android.xposed.XposedBridge")`，
 *    类能加载出来就判定「装了 Xposed」。
 * 2. **抓异常堆栈** —— 抛一个异常，看栈里有没有 `de.robv.android.xposed.*` 帧；
 *    Xposed 的 hook 会把框架帧留在调用链上，这是最难藏的一种痕迹。
 * 3. **查已安装包** —— `getPackageInfo("com.yjp.tgenhance")` 查得到就说明模块存在。
 *
 * ### 刻意不做的事
 *
 * 不伪造 `Build` 属性、不隐藏 root、不动 `/proc/self/maps`。
 * 那些属于「root 隐藏」，是全局环境改造，不该由一个 Telegram 增强模块去代劳 ——
 * 本模块只负责让自己的痕迹消失。
 *
 * ### 一个实现上的坑
 *
 * 抛 `ClassNotFoundException` / `NameNotFoundException` 的回调**不能**用
 * [XLog.guard] 包裹：那个包装会捕获异常，反而把本该抛出去的异常吃掉，
 * 于是「隐身」静默失效。这类回调只做前置判断，不写可能失败的逻辑。
 */
object StealthHooks {

    /** 需要隐藏的包前缀。首字符预筛见 [hiddenByPrefix]，避免拖慢每次类加载。 */
    private val HIDDEN_PREFIXES = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed",
        "com.yjp.tgenhance",
    )

    fun install(classLoader: ClassLoader) {
        XLog.section("反检测")
        XLog.i(
            "[反检测] 配置快照：隐藏模块痕迹=${Prefs.hideXposed}" +
                "（运行期实时读取，改设置无需重启）"
        )

        hookClassLoader(classLoader)
        hookStackTrace(classLoader, "java.lang.Throwable")
        hookStackTrace(classLoader, "java.lang.Thread")
        hookPackageQuery(classLoader)
    }

    // ------------------------------------------------------------------
    // 1. 类加载隐身
    // ------------------------------------------------------------------

    private fun hookClassLoader(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("java.lang.ClassLoader", classLoader)
        if (cls == null) {
            XLog.e("[反检测] 未找到 java.lang.ClassLoader")
            return
        }

        safe("类加载隐身") {
            XposedBridge.hookAllMethods(cls, "loadClass", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Prefs.hideXposed) return
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!hiddenByPrefix(name)) return
                    HookStats.hit("stealth.classLoad")
                    // 注意：这里必须真的抛出去，不能包 guard
                    throw ClassNotFoundException(name)
                }
            })
            XLog.result("反检测", "ClassLoader.loadClass() 已接管：框架相关类的查询将被拒绝")
        }
    }

    // ------------------------------------------------------------------
    // 2. 堆栈隐身
    // ------------------------------------------------------------------

    private fun hookStackTrace(classLoader: ClassLoader, className: String) {
        val cls = XposedHelpers.findClassIfExists(className, classLoader) ?: return

        safe("堆栈隐身·$className") {
            XposedBridge.hookAllMethods(cls, "getStackTrace", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("堆栈隐身") {
                        if (!Prefs.hideXposed) return@guard
                        val raw = param.result as? Array<*> ?: return@guard
                        val frames = raw.filterIsInstance<StackTraceElement>()
                        if (frames.isEmpty()) return@guard

                        val kept = frames.filterNot { hiddenByPrefix(it.className) }
                        if (kept.size == frames.size) return@guard

                        HookStats.hit("stealth.stackTrace")
                        param.result = kept.toTypedArray()
                    }
                }
            })
            XLog.result("反检测", "$className.getStackTrace() 已接管：框架帧将被剔除")
        }
    }

    // ------------------------------------------------------------------
    // 3. 包名隐身
    // ------------------------------------------------------------------

    private fun hookPackageQuery(classLoader: ClassLoader) {
        // PackageManager 是接口，实际实现是 ApplicationPackageManager
        val cls = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", classLoader
        )
        if (cls == null) {
            XLog.w("[反检测] 未找到 ApplicationPackageManager，包名隐身不可用")
            return
        }

        safe("包名隐身") {
            XposedBridge.hookAllMethods(cls, "getPackageInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Prefs.hideXposed) return
                    val pkg = param.args.getOrNull(0) as? String ?: return
                    if (pkg != Prefs.MODULE_PKG) return
                    HookStats.hit("stealth.pkgQuery")
                    // 同上：必须真的抛出去
                    throw PackageManager.NameNotFoundException(pkg)
                }
            })
            XLog.result("反检测", "getPackageInfo() 已接管：模块自身包名的查询将被拒绝")
        }
    }

    // ------------------------------------------------------------------

    /**
     * 判断名字是否命中隐藏前缀。
     *
     * 类加载是启动期最高频的操作之一（动辄几千次），这里先用首字符做一次
     * 廉价预筛，只有首字符可能命中的才走 `startsWith` 全量比对。
     * 四个前缀的首字符分别是 d / o / i / c。
     */
    private fun hiddenByPrefix(name: String): Boolean {
        if (name.length < 6) return false
        return when (name[0]) {
            'd', 'o', 'i', 'c' -> HIDDEN_PREFIXES.any { name.startsWith(it) }
            else -> false
        }
    }
}
