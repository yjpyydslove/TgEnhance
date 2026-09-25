package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import de.robv.android.xposed.XposedHelpers
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 反检测的**自我验证**（v N1.2）。
 *
 * ### 为什么需要这个
 *
 * 在此之前，「隐藏模块痕迹」是一个用户只能选择相信的开关 ——
 * 界面显示开着，但到底有没有挡住探测、挡住了几条，没有任何反馈。
 * 一旦某条拦截因为宿主版本变化而失效，用户和我都不会知道，
 * 直到某个客户端开始弹「检测到 Xposed」。
 *
 * 这里做的是：**用检测方的手法去探测自己**，把结果摊在自检报告里。
 *
 * ### 与 [StealthHooks] 的关系
 *
 * [StealthHooks] 负责「挡」，本文件负责「验证挡没挡住」。
 * 两者共用同一份前缀判定，确保「挡的」和「验的」永远是同一套标准 ——
 * 如果各写一份，改了一处忘了另一处，自检就会给出错误的安心感。
 *
 * ### 触发时机
 *
 * 在 [com.yjp.tgenhance.diag.Diagnostics.run] 里调用，也就是**挂载流程的最后**。
 * 必须是最后面：验证的是「前面所有拦截都装好之后」的效果。
 */
object StealthSelfTest {

    /** 一条验证结果。 */
    data class Probe(
        /** 探测项名称，面向用户。 */
        val name: String,
        /** 是否被成功挡住。 */
        val blocked: Boolean,
        /** 补充说明（挡住时说明怎么挡的，没挡住说明原因）。 */
        val detail: String
    )

    /**
     * 跑一遍全部探测。返回的每一项都对应一种真实的检测手法。
     *
     * 注意 [Probe.blocked] 的语义是「**我们已经挡住它了**」——
     * 为 true 是好结果。这个正负关系很容易在渲染时搞反，
     * 所以刻意用 `blocked` 而不是 `ok` 来命名，让调用点一眼能看清。
     */
    fun run(classLoader: ClassLoader): List<Probe> {
        // 开关没开就没必要验：所有拦截都会放行，验出来全是「没挡住」，
        // 只会制造误导。直接说明「未开启」更清楚。
        if (!Prefs.hideXposed) {
            return listOf(
                Probe(
                    name = "隐藏模块痕迹",
                    blocked = false,
                    detail = "未开启，以下探测均未拦截"
                )
            )
        }

        return listOf(
            probeClassLoad(classLoader),
            probeStackFrame(),
            probeAllStackTraces(),
            probeStackTracePrint(),
            probeInstalledPackages(),
            probeInstalledApplications(),
            probeOwnPackage(),
            probeModifiers()
        )
    }

    /**
     * 探测 1：能不能按类名加载框架类。
     *
     * 这是最朴素的一种检测。我们用 [StealthHooks] 拦住的那条路径去试 ——
     * 为了确保验的是同一个东西，这里**复用同一份前缀列表**。
     *
     * 用 `Class.forName` 而不是直接 `XposedHelpers.findClass`：
     * 前者走 `ClassLoader.loadClass`，正是拦截点；后者是框架自己的入口，
     * 绕过拦截，验不出真实情况。
     */
    private fun probeClassLoad(classLoader: ClassLoader): Probe {
        val target = StealthHooks.hiddenPrefixes.first()
        val loaded = try {
            Class.forName(target + ".XposedBridge", false, classLoader)
            true
        } catch (t: Throwable) {
            false
        }
        return Probe(
            name = "按类名探测框架",
            blocked = !loaded,
            detail = if (!loaded) "已拒绝加载 $target.*" else "仍可加载 $target.*，拦截未生效"
        )
    }

    /**
     * 探测 2：异常堆栈里有没有框架帧。
     *
     * 检测方最常用的一招：主动造一个异常，检查栈里有没有
     * `de.robv.android.xposed.*`。这里用同样的方式验自己。
     *
     * 注意：**不能**用 `catch` 包住 `Class.forName` 再取栈 ——
     * 那样栈里会有我们自己的帧，且位置不固定。直接 new 一个 Throwable
     * 在当前调用点取栈，看有没有混进框架帧即可。
     */
    private fun probeStackFrame(): Probe {
        val frames = try {
            Throwable().stackTrace
        } catch (t: Throwable) {
            return Probe("异常堆栈探测", false, "无法获取堆栈，跳过")
        }
        val leaked = frames.filter { StealthHooks.isHiddenName(it.className) }
        return Probe(
            name = "异常堆栈探测",
            blocked = leaked.isEmpty(),
            detail = if (leaked.isEmpty()) {
                "栈中 ${frames.size} 个帧，无框架帧"
            } else {
                "仍有 ${leaked.size} 个框架帧暴露（${leaked.first().className}）"
            }
        )
    }

    /**
     * 探测 3：扫**所有线程**的栈，看有没有框架帧。
     *
     * 前一项只验了「当前线程的栈」。检测方更愿意一次拿全部线程的栈 ——
     * 一次调用就能看到所有线程上有没有框架痕迹，比逐线程取高效得多，
     * 收获也更大：我们的 hook 回调可能停在任何一个线程上。
     *
     * 这一项对应 `Thread.getAllStackTraces()` 那条拦截。不单独验的话，
     * 那条拦截失效时自检照样全绿，等于白挡。
     */
    private fun probeAllStackTraces(): Probe {
        val scanned = try {
            Thread.getAllStackTraces()
        } catch (t: Throwable) {
            return Probe("全线程堆栈探测", false, "无法获取全线程栈，跳过")
        }
        if (scanned.isEmpty()) {
            return Probe("全线程堆栈探测", false, "未取到任何线程栈，跳过")
        }

        var frames = 0
        var leaked = 0
        var sample: String? = null
        for ((_, stack) in scanned) {
            for (frame in stack) {
                frames++
                if (StealthHooks.isHiddenName(frame.className)) {
                    leaked++
                    if (sample == null) sample = frame.className
                }
            }
        }
        return Probe(
            name = "全线程堆栈探测",
            blocked = leaked == 0,
            detail = if (leaked == 0) {
                "扫 ${scanned.size} 个线程共 $frames 个帧，无框架帧"
            } else {
                "仍有 $leaked 个框架帧暴露（$sample）"
            }
        )
    }

    /**
     * 探测 4：`printStackTrace` 的输出里有没有框架痕迹。
     *
     * 这一条**必须单独验**，不能靠前两项代替：`printStackTrace` 内部走的是
     * `Throwable.getOurStackTrace()`，而**公开的 `getStackTrace()` 不经过它**
     * （那边另有一份 clone 逻辑）。两条路径各自被拦，也就得各自被验。
     *
     * 做法和检测方一样：把异常打到内存流里，再搜关键字。
     * 输出里既不该有 `de.robv.android.xposed.*`，也不该有本模块的包名 ——
     * 后者同样会被剔除（`com.yjp.tgenhance` 在前缀名单里）。
     */
    private fun probeStackTracePrint(): Probe {
        val text = try {
            val buffer = StringWriter()
            val writer = PrintWriter(buffer)
            Throwable("tgenhance-probe").printStackTrace(writer)
            writer.flush()
            buffer.toString()
        } catch (t: Throwable) {
            return Probe("堆栈打印探测", false, "无法捕获打印输出，跳过")
        }
        if (text.isEmpty()) {
            return Probe("堆栈打印探测", false, "打印输出为空，跳过")
        }

        val leaked = StealthHooks.hiddenPrefixes.filter { text.contains(it) }
        return Probe(
            name = "堆栈打印探测",
            blocked = leaked.isEmpty(),
            detail = if (leaked.isEmpty()) {
                "printStackTrace 输出中无框架痕迹"
            } else {
                "输出中仍含 ${leaked.joinToString("、")}"
            }
        )
    }

    /**
     * 探测 5：模块是否出现在「已安装包」列表里。
     *
     * 用 `AndroidAppHelper` 拿当前 Application 再查 —— 和检测方的做法一致。
     * 用 `PackageManager.GET_META_DATA` 是为了同时验证 meta-data 有没有被读到。
     */
    private fun probeInstalledPackages(): Probe = try {
        val app = android.app.AndroidAppHelper.currentApplication()
        if (app == null) {
            Probe("已安装包列表探测", false, "未取到 Application，跳过")
        } else {
            val found = app.packageManager
                .getInstalledPackages(0)
                .any { it.packageName == Prefs.MODULE_PKG }
            Probe(
                name = "已安装包列表探测",
                blocked = !found,
                detail = if (!found) "列表中已剔除本模块" else "列表中仍能看到本模块包名"
            )
        }
    } catch (t: Throwable) {
        Probe("已安装包列表探测", false, "查询失败：${t.javaClass.simpleName}")
    }

    /**
     * 探测 6：模块是否出现在「已安装应用」列表里。
     *
     * 与探测 5 是**两条不同的代码路径**：那个返回 `PackageInfo`，
     * 这个返回 `ApplicationInfo`。检测方两条都会试，
     * 所以拦截和自检都得成对 —— 只拦一条、只验一条，等于留了个后门。
     */
    private fun probeInstalledApplications(): Probe = try {
        val app = android.app.AndroidAppHelper.currentApplication()
        if (app == null) {
            Probe("已安装应用列表探测", false, "未取到 Application，跳过")
        } else {
            val found = app.packageManager
                .getInstalledApplications(0)
                .any { it.packageName == Prefs.MODULE_PKG }
            Probe(
                name = "已安装应用列表探测",
                blocked = !found,
                detail = if (!found) {
                    "getInstalledApplications 中已剔除本模块"
                } else {
                    "列表中仍能看到本模块包名"
                }
            )
        }
    } catch (t: Throwable) {
        Probe("已安装应用列表探测", false, "查询失败：${t.javaClass.simpleName}")
    }

    /**
     * 探测 7：按包名直接查模块。
     *
     * 与探测 3 的区别：那个是「列全部再找」，这个是「指名道姓地查」。
     * 两者走不同的代码路径，拦截点也不同，所以分开验。
     *
     * 顺带验证 meta-data：检测方读 `xposedmodule` 比读包名更致命，
     * 因为那直接证明「这是个 Xposed 模块」而不只是「装了这个应用」。
     */
    private fun probeOwnPackage(): Probe {
        val app = try {
            android.app.AndroidAppHelper.currentApplication()
        } catch (t: Throwable) {
            null
        }
        if (app == null) return Probe("按包名探测模块", false, "未取到 Application，跳过")

        return try {
            val info = app.packageManager.getApplicationInfo(
                Prefs.MODULE_PKG,
                android.content.pm.PackageManager.GET_META_DATA
            )
            // 走到这里说明没抛异常 —— 拦截本该抛 NameNotFoundException，也就是没生效。
            // 但仍要区分「拿到了但 meta-data 是空的」：那种情况下虽然查得到包，
            // 至少没有直接暴露「这是 Xposed 模块」这个事实。
            val hasMeta = info.metaData?.containsKey("xposedmodule") == true
            Probe(
                name = "按包名探测模块",
                blocked = false,
                detail = if (hasMeta) {
                    "模块元数据可读 —— 拦截未生效"
                } else {
                    "包可查但元数据未暴露"
                }
            )
        } catch (t: Throwable) {
            // 抛 NameNotFoundException 正是我们想要的：说明拦截生效了
            val name = t.javaClass.simpleName
            Probe(
                name = "按包名探测模块",
                blocked = name.contains("NameNotFound"),
                detail = if (name.contains("NameNotFound")) {
                    "已抛出 NameNotFoundException"
                } else {
                    "查询异常：$name"
                }
            )
        }
    }

    /**
     * 探测 8：被本模块 hook 过的方法是否还带 native 标志。
     *
     * 这是最隐蔽也最致命的一种检测：逐个反射目标类的方法，
     * 看哪个 Java 方法变成了 native。
     *
     * ### 为什么不能直接用 `method.modifiers`
     *
     * 因为**我们自己 hook 了 `Method.getModifiers()`** ——
     * 拿到的永远是抹平之后的值，测出来必然「通过」，这个自检就没有意义了。
     * 所以这里绕过自己的拦截，用反射直接读 `Method` 对象内部持有的
     * 修饰符字段，看到的是 ART 里的**真实**状态。
     *
     * 字段名在不同 Android 版本上是 `modifiers`（`java.lang.reflect.Method`
     * 自 AOSP 起一直是这个）。读不到就降级为「无法验证」，
     * 不假装通过 —— 一个永远说「没问题」的自检比没有自检更危险。
     */
    private fun probeModifiers(): Probe {
        val sample = StealthHooks.firstHookedMethod()
            ?: return Probe("native 标志探测", true, "尚无已 hook 方法可验")

        val real = readRealModifiers(sample)
            ?: return Probe(
                "native 标志探测",
                false,
                "无法绕过自身的 getModifiers 拦截，跳过验证"
            )

        val exposed = java.lang.reflect.Modifier.isNative(real)
        return Probe(
            name = "native 标志探测",
            blocked = !exposed,
            detail = if (!exposed) {
                "抽查 ${sample.declaringClass.simpleName}.${sample.name}，标志已抹平"
            } else {
                "${sample.declaringClass.simpleName}.${sample.name} 仍暴露 native 标志"
            }
        )
    }

    /**
     * 绕过 `Method.getModifiers()` 直接读字段，拿到 ART 里的真实修饰符。
     *
     * 读不到时返回 null（不返回 0）—— 0 是合法值（一个包级私有方法），
     * 用它代替「读取失败」会被误判成「没有 native 标志」，也就是假通过。
     */
    private fun readRealModifiers(method: java.lang.reflect.Method): Int? = try {
        val field = java.lang.reflect.Method::class.java.getDeclaredField("modifiers")
        field.isAccessible = true
        field.getInt(method)
    } catch (t: Throwable) {
        null
    }

    /** 最近一次探测结果，供自检渲染读取。 */
    @Volatile
    var lastResult: List<Probe> = emptyList()
        private set

    /** 跑一遍并存下结果。 */
    fun runAndStore(classLoader: ClassLoader): List<Probe> {
        val result = try {
            run(classLoader)
        } catch (t: Throwable) {
            emptyList()
        }
        lastResult = result
        return result
    }
}
