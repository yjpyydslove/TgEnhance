package com.yjp.tgenhance.hooks

/**
 * 一个 Hook 点的声明。
 */
data class HookPoint(
    /** 触发计数用的 key，例如 `privacy.typing`。 */
    val statKey: String,
    /** 面向用户的中文名，用于设置界面「运行状态」与日志。 */
    val label: String
)

/**
 * 全部 Hook 点的清单（v5.0.0）。
 *
 * 和 v3.0.0 的 [com.yjp.tgenhance.core.Features]（功能注册表）是同一个思路，
 * 只是下沉一层：**统计 key 与它的中文名此前分散在两处** ——
 * hook 端在 `HookEntry` 里 `expect(...)` 逐个登记，界面端在
 * `SettingsActivity.HOOK_LABELS` 里再维护一份映射。
 * 新增一个 Hook 点时忘掉任何一边，症状分别是：
 *
 * - 忘登记 -> 该点在「运行状态」里永远不出现，看不出它到底有没有生效
 * - 忘加映射 -> 界面直接显示 `privacy.foo` 这种原始 key
 *
 * 现在两边都从这张表派生，结构上不可能不一致。
 *
 * 注意：本文件不引用任何 Xposed API，因此设置界面进程也能安全加载。
 */
object HookCatalog {

    val ALL: List<HookPoint> = listOf(
        // 多账号
        HookPoint("account.maxCount", "账号上限查询"),
        HookPoint("account.expand", "账号数组扩容"),

        // 界面与主题
        HookPoint("ui.typeface.seen", "字体加载请求"),
        HookPoint("ui.typeface.replaced", "字体替换"),
        HookPoint("ui.stories", "Stories 显示查询"),
        HookPoint("ui.tablet", "平板布局判定"),
        HookPoint("ui.updateCheck", "更新检查"),

        // 网络
        HookPoint("net.proxyProbe", "代理连通性探测"),
        HookPoint("net.autoDownload.blocked", "拦截自动下载"),
        HookPoint("net.autoplay.blocked", "拦截自动播放"),

        // 隐私与本地增强
        HookPoint("privacy.typing", "输入状态发送"),
        HookPoint("privacy.deleteMessages", "消息删除"),
        HookPoint("privacy.recall.blocked", "拦截撤回"),
        HookPoint("privacy.readReceipt.blocked", "拦截已读上报"),
        HookPoint("privacy.hideOnline", "隐藏在线状态"),
        HookPoint("privacy.peerOnline", "隐藏对方在线"),
        HookPoint("privacy.phoneMask", "手机号遮罩"),

        // 广告屏蔽
        HookPoint("ads.sponsored.blocked", "屏蔽赞助消息"),

        // 反检测
        HookPoint("stealth.classLoad", "拒绝框架类查询"),
        HookPoint("stealth.stackTrace", "剔除框架堆栈帧"),
        HookPoint("stealth.pkgQuery", "拒绝模块包名查询"),
        HookPoint("stealth.pkgList", "清除包列表条目"),
        HookPoint("stealth.modifiers", "抹除 native 标志"),

        // 框架
        HookPoint("prefs.reload", "配置热更新"),
    )

    /** 全部计数 key，供 `HookStats.expect` 一次性登记。 */
    val statKeys: List<String> = ALL.map { it.statKey }

    /** 计数 key -> 中文名。 */
    val labels: Map<String, String> = ALL.associate { it.statKey to it.label }

    /** 取某个计数点的展示名；未知 key 原样返回，便于发现遗漏。 */
    fun labelOf(statKey: String): String = labels[statKey] ?: statKey
}
