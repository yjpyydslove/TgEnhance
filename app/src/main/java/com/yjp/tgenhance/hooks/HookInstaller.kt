package com.yjp.tgenhance.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Hook 安装的统一入口。
 *
 * 比直接用 `XposedBridge.hookAllMethods` 多做一件事：**登记被 hook 的方法**。
 *
 * Xposed 挂载一个方法时，会把它在 ART 里的实现替换成 native stub ——
 * 于是 `Modifier.isNative(method.getModifiers())` 从 false 变成 true。
 * 这是目前最可靠的一种 Xposed 检测手法：遍历目标类的所有方法，
 * 哪个「看起来是 Java 方法却带 native 标志」，哪个就被 hook 了。
 *
 * [StealthHooks] 需要这份名单才能在反射层把标志抹回去，
 * 所以**所有 hook 都必须经由本对象安装**（或至少先调用 `markHooked`）。
 */
object HookInstaller {

    /** 按方法名挂载全部重载，返回成功挂载的数量。 */
    fun hookAllByName(cls: Class<*>, methodName: String, callback: XC_MethodHook): Int {
        val methods = try {
            cls.declaredMethods.filter { it.name == methodName }
        } catch (t: Throwable) {
            return 0
        }

        var count = 0
        for (method in methods) {
            if (hookMethodQuietly(method, callback)) count++
        }
        return count
    }

    /**
     * 挂载单个方法。失败时返回 false 而不抛异常 ——
     * 个别重载挂不上不该拖垮整组 Hook。
     */
    fun hookMethodQuietly(method: Method, callback: XC_MethodHook): Boolean = try {
        // 挂载前若已是 native，说明已经被别的模块 hook 过（正常 Java 方法不该带这个标志）
        if (isAlreadyHooked(method)) ConflictWatch.note(method)

        StealthHooks.markHooked(method)
        XposedBridge.hookMethod(method, callback)
        true
    } catch (t: Throwable) {
        false
    }

    private fun isAlreadyHooked(method: Method): Boolean = try {
        Modifier.isNative(method.modifiers)
    } catch (t: Throwable) {
        false
    }
}
