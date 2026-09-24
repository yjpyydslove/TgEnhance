package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.XLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
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
        /** 参数个数下限（含）。用于应对「重载增删」这类改动。 */
        minParamCount: Int? = null,
        /** 参数个数上限（含）。 */
        maxParamCount: Int? = null,
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
            if (minParamCount != null && m.parameterCount < minParamCount) continue
            if (maxParamCount != null && m.parameterCount > maxParamCount) continue

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
     * 先按显式名单精确命中；**只在精确名单全部落空时**，才退到特征匹配兜底。
     *
     * ### 为什么是「二选一」而不是「取并集」（v N1.3 修正）
     *
     * 早先的实现是把精确结果与特征结果**并集**返回，这与本函数的文档承诺相矛盾：
     *
     * - 显式名单的意义是「**不误伤**」—— `deleteMessages` 该挂，而同族的
     *   `deleteMessagesByChatId` 不该挂（它会调用前者，挂上就成双重处理）。
     * - 特征兜底的意义是「**官方改名后还能命中**」—— 是保险，不是主力。
     *
     * 并集把两者混在一起，于是只要特征够宽（`nameContains = "deletemessage"`），
     * 那些**故意被排除**的方法照样会被捞进来一起 hook。
     * 更糟的是这种误伤只在「特征还没被触发」时看不出来 ——
     * 一旦官方改名导致精确落空，误伤面反而会**同时**扩大。
     *
     * 改成二选一之后，语义是清晰的：
     *  - 精确名单有命中 → 用精确的，特征完全不参与，误伤零。
     *  - 精确名单全落空 → 说明官方改名了，此时才启用特征兜底，
     *    并记入 [fuzzyMatched]，让自检报告显式提示「本 Hook 点已靠特征勉强接住」。
     *
     * ### 传了 explicitNames 但没传特征参数时
     *
     * 精确全落空就直接返回空列表（没有兜底可用），调用方会走「功能不可用」分支，
     * 这是正确行为 —— 比拿一个特征乱猜的方法去 hook 安全得多。
     */
    fun match(
        cls: Class<*>,
        explicitNames: List<String> = emptyList(),
        returnType: Class<*>? = null,
        namePrefix: String? = null,
        nameContains: String? = null,
        paramCount: Int? = null,
        minParamCount: Int? = null,
        maxParamCount: Int? = null
    ): List<Method> {
        val exact = explicitNames
            .mapNotNull { name ->
                // 这里要先按「返回类型 / 参数个数」筛一遍再比名字：
                // 同名重载可能有多个，签名不符的那个不该被选中
                findMethods(
                    cls,
                    returnType = returnType,
                    paramCount = paramCount,
                    minParamCount = minParamCount,
                    maxParamCount = maxParamCount
                ).firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            .distinctBy { simpleSignature(it) }

        if (exact.isNotEmpty()) {
            // 精确命中：特征不参与。这是绝大多数版本上的正常路径。
            return exact
        }

        // 精确全落空 —— 官方改名了。此时特征兜底才上场，并如实记账。
        //
        // 但先要过一道安全闸：**一个特征条件都不给**时，findMethods 不做名字过滤，
        // 会把「该类型下的全部方法」返回 —— 拿它当兜底等于把整个类都 hook 了。
        // 旧实现里这条路径是活的：三个只传 explicitNames 的调用点
        // （强制平板 / 关闭更新 / 隐藏手机号）一旦官方改名，就会把
        // AndroidUtilities 里每一个 boolean 方法强行改成 true，界面直接错乱。
        // 所以这里明确拒绝无特征兜底 —— 宁可让功能走「不可用」分支。
        if (namePrefix == null && nameContains == null) {
            XLog.w(
                "[适配] ${cls.simpleName} 的精确名单 ${explicitNames.joinToString(",")} 已全部落空，" +
                    "且未提供特征兜底条件 —— 本次不做任何 hook，避免误伤整类方法"
            )
            return emptyList()
        }

        val fuzzy = findMethods(
            cls,
            returnType = returnType,
            namePrefix = namePrefix,
            nameContains = nameContains,
            paramCount = paramCount,
            minParamCount = minParamCount,
            maxParamCount = maxParamCount
        )
        for (method in fuzzy) {
            fuzzyHits.add(cls.simpleName + "." + method.name)
        }
        return fuzzy.distinctBy { simpleSignature(it) }
    }

    /**
     * 靠特征兜底（而非精确方法名）命中的 Hook 点。
     *
     * 这是个很有用的适配质量指标：正常情况下应该一条都没有 ——
     * 一旦出现，说明官方改了这个方法的名字，而模块是靠特征匹配侥幸接住的。
     * 这种情况必须知道，否则下次改动幅度再大一点就会彻底失效。
     *
     * v N1.3 起语义变精确了：**只有 [match] 走到「精确名单全落空、启用特征兜底」
     * 这条分支时才会记账**。早先的实现是把精确与特征取并集，于是
     * 「某个方法名字恰好在精确名单里、同时又被特征捞到」也会被记成兜底命中，
     * 报告里就会出现假警报 —— 用户看到「官方改名了」其实什么都没发生。
     */
    private val fuzzyHits: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * 某个方法是否是靠特征兜底命中的。
     *
     * v N1.3 起要留意它的**边界**：只有「精确名单整体落空」才会记账，
     * 所以对精确名单里的名字调用它**一定是 false** —— 那正是「精确命中了」的
     * 通常情形。想判断「官方有没有改名」，应该看 [fuzzyMatched] 的返回值，
     * 而不是拿精确名单里的名字来问这里。
     */
    fun isFuzzyMatched(cls: Class<*>, methodName: String): Boolean = try {
        fuzzyHits.contains(cls.simpleName + "." + methodName)
    } catch (t: Throwable) {
        false
    }

    /**
     * 全部靠特征兜底命中的 Hook 点，形如 `类简名.方法名`。
     *
     * 注意它**不是**「哪些方法被 hook 了」的清单，而是「哪些 Hook 点的名字已经
     * 和官方源码对不上」的清单。自检报告用它提示适配风险，见
     * [com.yjp.tgenhance.diag.Diagnostics.fuzzyMatchCheck]。
     */
    fun fuzzyMatched(): List<String> = try {
        fuzzyHits.sorted()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 供日志阅读的短签名，不输出修饰符与包名。 */
    fun readableSignature(m: Method): String =
        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})"

    /** 方法唯一标识，用于去重（同名 + 同参数类型）。 */
    private fun simpleSignature(m: Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"
}
