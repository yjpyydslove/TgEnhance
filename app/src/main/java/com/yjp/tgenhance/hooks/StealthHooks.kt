package com.yjp.tgenhance.hooks

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

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
        hookAllStackTraces(classLoader)
        hookStackTracePrinting(classLoader)
        hookPackageQuery(classLoader)
        hookApplicationInfoQuery(classLoader)
        hookInstalledPackages(classLoader)
        hookMethodModifiers(classLoader)
    }

    // ------------------------------------------------------------------
    // 0. 被 hook 方法的登记（供第 4 项反检测使用）
    // ------------------------------------------------------------------

    /** 被本模块 hook 过的方法签名：`类名#方法名(参数类型)`。 */
    private val hookedSignatures: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /**
     * 登记一个被 hook 的方法。
     *
     * 必须由 [HookInstaller] 在 `XposedBridge.hookMethod` **之前**调用 ——
     * 那时 `parameterTypes` 还是正常的，方法也还没被替换成 native stub。
     */
    fun markHooked(method: Method) {
        try {
            hookedSignatures.add(signatureOf(method))
        } catch (t: Throwable) {
            // 登记失败只影响隐身，不影响 Hook 本身
        }
    }

    private fun signatureOf(method: Method): String =
        method.declaringClass.name + "#" + method.name +
            "(" + method.parameterTypes.joinToString(",") { it.name } + ")"

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
            HookInstaller.hookAllByName(cls, "loadClass", object : XC_MethodHook() {
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
            HookInstaller.hookAllByName(cls, "getStackTrace", object : XC_MethodHook() {
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

    /**
     * 接管 `Thread.getAllStackTraces()`（v N1.1）。
     *
     * 前一项只挡了「取当前线程的栈」，但检测方更愿意扫**所有线程**的栈 ——
     * 一次调用就能看到全部线程上有没有框架帧，比逐个线程去取高效得多，
     * 收获也更大（我们的 hook 回调可能停在任何一个线程上）。
     *
     * 返回类型是 `Map<Thread, StackTraceElement[]>`，需要重建这个 map。
     * 只在真的剔掉了东西时才替换结果，否则原样放行 ——
     * 每次都造一个新 map 会拖慢调用方（它可能是高频路径）。
     */
    private fun hookAllStackTraces(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("java.lang.Thread", classLoader) ?: return

        safe("全线程堆栈隐身") {
            HookInstaller.hookAllByName(cls, "getAllStackTraces", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("全线程堆栈隐身") {
                        if (!Prefs.hideXposed) return@guard
                        val raw = param.result as? Map<*, *> ?: return@guard
                        if (raw.isEmpty()) return@guard

                        var removed = 0
                        val cleaned = LinkedHashMap<Any?, Any?>(raw.size)
                        for ((thread, frames) in raw) {
                            if (frames !is Array<*>) {
                                cleaned[thread] = frames
                                continue
                            }
                            val kept = frames.filterNot { hiddenFrame(it) }
                            removed += frames.size - kept.size
                            cleaned[thread] = kept.toTypedArray()
                        }
                        if (removed == 0) return@guard

                        HookStats.hit("stealth.allStackTraces")
                        param.result = cleaned
                    }
                }
            })
            XLog.result("反检测", "Thread.getAllStackTraces() 已接管：各线程的框架帧将被剔除")
        }
    }

    /**
     * 接管 `Throwable.getOurStackTrace()`（v N1.1）。
     *
     * 为什么不是直接拦 `printStackTrace`：
     *
     * `printStackTrace` 有三个重载（无参 / `PrintStream` / `PrintWriter`），
     * 拦截它们要么得替换输出流（会破坏调用方自己的输出），要么得逐个重载处理。
     * 而它们**最终都走同一个私有方法** `getOurStackTrace()` 去取栈快照 ——
     * 拦这一个点，就同时覆盖了三条路径，也包括 `printStackTrace` 内部的递归
     * （它打印 cause 时会再次取栈）。
     *
     * ### 会不会误伤
     *
     * `getStackTrace()`（公开的那个）**不经过** `getOurStackTrace()` ——
     * 它自己有一份 clone 逻辑，所以前面 [hookStackTrace] 那条仍然必须保留，
     * 两者互不替代。
     *
     * 这个方法在 ART 里一直存在（`libcore` 的 `Throwable` 实现），
     * 但毕竟不是公开 API，所以用 `findAndHookMethod` 探测式挂载：
     * 找不到就安静跳过，不影响其余反检测项。
     */
    private fun hookStackTracePrinting(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("java.lang.Throwable", classLoader) ?: return

        safe("堆栈打印隐身") {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, "getOurStackTrace", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            guard("堆栈打印隐身") {
                                if (!Prefs.hideXposed) return@guard
                                val raw = param.result as? Array<*> ?: return@guard
                                val cleaned = raw.filterNot { hiddenFrame(it) }
                                if (cleaned.size == raw.size) return@guard

                                HookStats.hit("stealth.printStack")
                                param.result = cleaned.toTypedArray()
                            }
                        }
                    }
                )
                XLog.result("反检测", "Throwable.getOurStackTrace() 已接管：printStackTrace 输出中的框架帧将被剔除")
            } catch (t: Throwable) {
                // 该方法是 ART 内部实现，不是公开 API；某些 ROM 上可能没有。
                // 这里只降级，不报错 —— 前面那两条 getStackTrace 拦截仍在生效。
                XLog.i("[反检测] 未找到 Throwable.getOurStackTrace()，printStackTrace 的框架帧不会被剔除")
            }
        }
    }

    /** 判断一个 `StackTraceElement` 是否属于需要隐藏的框架帧。 */
    private fun hiddenFrame(frame: Any?): Boolean = try {
        val element = frame as? StackTraceElement ?: return false
        hiddenByPrefix(element.className)
    } catch (t: Throwable) {
        false
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
            HookInstaller.hookAllByName(cls, "getPackageInfo", object : XC_MethodHook() {
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
    // 3.5 包列表隐身
    // ------------------------------------------------------------------

    /**
     * 拦截针对模块包的 `getApplicationInfo`（v N1.1）。
     *
     * `getPackageInfo` 返回的是 `PackageInfo`，而**读 meta-data 更常用的是
     * `getApplicationInfo`** —— 检测方查 `xposedmodule` / `xposedminversion`
     * 这两个键，是「这是不是一个 Xposed 模块」最直接的证据，
     * 而且这个查询不经过 `getPackageInfo`，前面那条拦不到。
     *
     * 与 `getPackageInfo` 一样，抛 `NameNotFoundException`。
     * 注意 `getApplicationInfo` 在 API 33+ 有一个三参重载
     * （带 `ApplicationInfoFlags`），`hookAllByName` 会一并接管。
     */
    private fun hookApplicationInfoQuery(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", classLoader
        )
        if (cls == null) return

        safe("应用信息隐身") {
            val hooked = HookInstaller.hookAllByName(cls, "getApplicationInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Prefs.hideXposed) return
                    // 第一个参数在所有重载里都是包名
                    val pkg = param.args.getOrNull(0) as? String ?: return
                    if (pkg != Prefs.MODULE_PKG) return
                    HookStats.hit("stealth.appInfo")
                    throw PackageManager.NameNotFoundException(pkg)
                }
            })
            XLog.result("反检测", "getApplicationInfo() 已接管 $hooked 处：模块的 meta-data 不会被读到")
        }
    }

    /**
     * 从「已安装应用列表」里剔除本模块（v5.6.0）。
     *
     * 只拦 `getPackageInfo` 是不够的：检测方更常用的是**列一遍所有已安装应用**
     * 再按包名找可疑项 —— 这个查询不针对具体包名，前面那条拦截自然管不到。
     *
     * 做法是把返回列表里的模块条目滤掉。**不去改 QueryIntentActivities 之类** ——
     * 那会影响宿主正常的功能（比如分享目标、跳转判断），收益远小于风险。
     */
    private fun hookInstalledPackages(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", classLoader
        )
        if (cls == null) {
            XLog.w("[反检测] 未找到 ApplicationPackageManager，包列表隐身不可用")
            return
        }

        safe("包列表隐身") {
            var hooked = 0
            for (name in listOf("getInstalledPackages", "getInstalledApplications")) {
                hooked += HookInstaller.hookAllByName(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("包列表隐身") {
                            if (!Prefs.hideXposed) return@guard
                            val raw = param.result as? List<*> ?: return@guard

                            val filtered = raw.filterNot { isModuleEntry(it) }
                            if (filtered.size == raw.size) return@guard

                            HookStats.hit("stealth.pkgList")
                            param.result = filtered
                        }
                    }
                })
            }
            XLog.result("反检测", "已安装包列表查询已接管 $hooked 处：模块自身会被剔除")
        }
    }

    private fun isModuleEntry(entry: Any?): Boolean = try {
        when (entry) {
            is PackageInfo -> entry.packageName == Prefs.MODULE_PKG
            is ApplicationInfo -> entry.packageName == Prefs.MODULE_PKG
            else -> false
        }
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // 4. 方法修饰符隐身
    // ------------------------------------------------------------------

    /**
     * 抹掉被 hook 方法的 native 标志（v4.1.0）。
     *
     * Xposed 挂载一个方法时会把 ART 里的实现替换成 native stub，
     * 于是 `Modifier.isNative(m.getModifiers())` 从 false 变成 true。
     * 遍历目标类的方法、找出「明明是 Java 方法却带 native 标志」的那些，
     * 是目前最可靠的一种 Xposed 检测手法。
     *
     * 只对**本模块 hook 过的方法**修正（名单由 [markHooked] 维护），
     * 不做全量过滤 —— 全量过滤会把 Telegram 自己真正的 native 方法
     * （`ConnectionsManager.native_*` 之类）也伪装成 Java 方法，
     * 反而制造出新的异常特征。
     */
    private fun hookMethodModifiers(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("java.lang.reflect.Method", classLoader)
        if (cls == null) {
            XLog.e("[反检测] 未找到 java.lang.reflect.Method")
            return
        }

        safe("方法修饰符隐身") {
            HookInstaller.hookAllByName(cls, "getModifiers", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("修饰符隐身") {
                        if (!Prefs.hideXposed) return@guard
                        val method = param.thisObject as? Method ?: return@guard
                        if (!hookedSignatures.contains(signatureOf(method))) return@guard
                        val modifiers = param.result as? Int ?: return@guard
                        if (modifiers and Modifier.NATIVE == 0) return@guard
                        HookStats.hit("stealth.modifiers")
                        param.result = modifiers and Modifier.NATIVE.inv()
                    }
                }
            })
            XLog.result("反检测", "Method.getModifiers() 已接管：被 hook 的方法不再暴露 native 标志")
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
