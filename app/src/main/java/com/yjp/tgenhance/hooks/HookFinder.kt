package com.yjp.tgenhance.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * 按「方法特征」而不是写死的方法名定位 Hook 点。
 *
 * ### 为什么需要它
 *
 * Telegram 每个版本都可能调整内部方法命名。写死名字的 hook 一旦对不上，
 * 表现是「挂载日志一切正常，但功能完全没有效果」—— 这是最难排查的失败形态，
 * 因为任何静态检查都发现不了。
 *
 * 本工具用 **返回类型 + 名字特征 + 参数个数** 组合定位。例如「Stories 是否可见」
 * 这类查询，只要还是「返回 boolean 且名字里带 stor」的方法就会被命中，
 * 即便官方把 `hasStories` 改名成 `hasNewStories` 也不会失效。
 *
 * 用它拿到的 [Method] 直接交给 `XposedBridge.hookMethod(method, callback)`，
 * 比 `hookAllMethods(cls, name, ...)` 更精确：只挂真正匹配上的那一个。
 */
object HookFinder {

    /**
     * 在 `cls` 中查找满足全部条件的方法。
     *
     * @param returnType    要求的返回类型，传 `null` 表示不限
     * @param namePrefix    方法名前缀（忽略大小写）
     * @param nameContains  方法名需包含的子串（忽略大小写）
     * @param paramCount    参数个数，传 `null` 表示不限
     */
    fun findMethods(
        cls: Class<*>,
        returnType: Class<*>? = null,
        namePrefix: String? = null,
        nameContains: String? = null,
        paramCount: Int? = null,
        publicOnly: Boolean = false
    ): List<Method> {
        val out = ArrayList<Method>()
        val methods = try {
            cls.declaredMethods
        } catch (t: Throwable) {
            return out
        }
        for (m in methods) {
            if (m.isSynthetic || m.isBridge) continue
            if (publicOnly && !Modifier.isPublic(m.modifiers)) continue
            if (returnType != null && m.returnType != returnType) continue
            if (paramCount != null && m.parameterCount != paramCount) continue

            val name = m.name
            if (namePrefix != null && !name.startsWith(namePrefix, ignoreCase = true)) continue
            if (nameContains != null) {
                if (!name.lowercase(Locale.ROOT).contains(nameContains.lowercase(Locale.ROOT))) continue
            }
            out.add(m)
        }
        return out
    }

    /**
     * 先按显式名单精确命中，再用特征匹配兜底补漏，最后去重。
     *
     * 显式名单保证「不误伤」—— 例如 `hasLiveStory` 虽然也符合特征，
     * 但它表达的是「加载中」，强制 false 反而会打乱状态，故不列入名单；
     * 特征兜底则保证「官方改了名字还能命中」。
     */
    fun match(
        cls: Class<*>,
        explicitNames: List<String> = emptyList(),
        returnType: Class<*>? = null,
        namePrefix: String? = null,
        nameContains: String? = null
    ): List<Method> {
        val exact = explicitNames
            .mapNotNull { name ->
                findMethods(cls, returnType = returnType)
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        val fuzzy = findMethods(
            cls,
            returnType = returnType,
            namePrefix = namePrefix,
            nameContains = nameContains
        )
        return (exact + fuzzy).distinctBy { simpleSignature(it) }
    }

    /** 供日志阅读的短签名，不输出修饰符与包名。 */
    fun readableSignature(m: Method): String =
        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})"

    /** 方法唯一标识，用于去重（同名 + 同参数类型）。 */
    private fun simpleSignature(m: Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"
}
