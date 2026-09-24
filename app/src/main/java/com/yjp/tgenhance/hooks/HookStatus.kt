package com.yjp.tgenhance.hooks

import java.util.Collections

/**
 * 功能可用性登记。
 *
 * ### 解决什么问题
 *
 * 在某个客户端上，某个功能的 Hook 点可能**根本不存在** —— 老版本没有 StoriesController、
 * 某些 fork 删掉了 DownloadController、框架不支持跨进程配置等等。
 *
 * 此时模块的做法是「跳过挂载」并继续跑其他功能，这是对的；
 * 但用户的体感是「这个开关点了没反应」，而日志里的那一行 `未找到 XXX`
 * 混在二十多行挂载日志中间，没人会注意到。
 *
 * 所以这里把「哪些功能在当前客户端不可用」单独收集起来，
 * 在自检里汇总成一条，直接告诉用户「这几个开关在你这版上不会生效」。
 */
object HookStatus {

    /** 当前客户端上不可用的功能（存 [com.yjp.tgenhance.Prefs] 的配置 key）。 */
    private val unavailable: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /**
     * 登记一个不可用的功能。
     *
     * @param featureKey 对应 `Prefs` 里的配置 key，便于回显时查出功能名称
     */
    fun markUnavailable(featureKey: String) {
        unavailable.add(featureKey)
    }

    fun unavailableKeys(): List<String> = try {
        unavailable.sorted()
    } catch (t: Throwable) {
        emptyList()
    }
}
