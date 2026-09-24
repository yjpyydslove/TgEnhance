package com.yjp.tgenhance.hooks

import java.lang.reflect.Method
import java.util.Collections

/**
 * 其他模块的 Hook 冲突记录。
 *
 * ### 怎么发现的
 *
 * 复用 [StealthHooks] 的那套原理：Xposed 挂载一个方法时，会把它在 ART 里的实现
 * 换成 native stub。所以在**我们自己挂载之前**，如果目标方法的修饰符里已经带
 * `NATIVE`，就说明已经有别人 hook 过它了。
 *
 * 这个判断不需要读任何框架内部状态（XposedBridge 的私有字段名在不同实现之间
 * 并不一致），只需要一次反射查询，而且没有误报空间 —— 我们要 hook 的都是普通
 * Java 方法，本就不该是 native。
 *
 * ### 为什么值得记录
 *
 * 两个模块 hook 同一个方法时的表现非常难查：可能互相覆盖导致其中一个静默失效，
 * 也可能因为优先级不同出现「有时生效有时不生效」。在自检里点名出来，
 * 至少能让用户知道该去关掉哪个模块，而不是对着「功能没反应」干瞪眼。
 */
object ConflictWatch {

    /** 检测到已被其他模块占用过的方法，格式：`类简名.方法名`。 */
    private val conflicts: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /** 登记一个「挂载前就已是 native」的方法。 */
    fun note(method: Method) {
        try {
            val owner = method.declaringClass.simpleName
            conflicts.add("$owner.${method.name}")
        } catch (t: Throwable) {
            // 记录失败不影响 Hook 本身
        }
    }

    fun list(): List<String> = try {
        conflicts.sorted()
    } catch (t: Throwable) {
        emptyList()
    }

    val size: Int get() = conflicts.size
}
