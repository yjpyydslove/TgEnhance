package com.yjp.tgenhance.core

import com.yjp.tgenhance.Prefs

/**
 * 设置界面的分组。**枚举声明顺序即界面顺序**。
 */
enum class FeatureGroup(val title: String) {
    ACCOUNT("多账号"),
    UI("界面与主题"),
    NETWORK("网络"),
    PRIVACY("隐私与本地增强"),
    DIAG("诊断"),
}

/** 功能的副作用级别；非 [NONE] 的项在开启前必须弹确认。 */
enum class RiskLevel { NONE, MEDIUM, HIGH }

/**
 * 一个功能的完整声明。
 *
 * ### 为什么要这张表
 *
 * 在此之前，「配置 key / 界面标题 / 说明文案 / 风险等级 / 关闭全部功能是否包含它」
 * 分散在 `Prefs`、`SettingsActivity`、`HookStats.expect` 三个地方，
 * 新增一个功能要改五六个文件，漏一处就出现两种典型故障：
 * 开关点了没反应（key 没进配置清单），或界面直接显示原始的 `privacy.foo`。
 *
 * 现在集中声明，界面与配置清单从同一份数据派生，**结构上不可能不一致**。
 *
 * 注意：这里只描述「界面与开关」，不描述 Hook 点本身 ——
 * 后者在各自 `*Hooks` 文件里，因为它依赖具体的方法签名，改动频繁得多。
 */
data class FeatureSpec(
    val key: String,
    val group: FeatureGroup,
    val title: String,
    val summary: String,
    val risk: RiskLevel = RiskLevel.NONE,
    val riskMessage: String? = null,
    val default: Boolean = false,
    /**
     * 是否属于「分组总开关」。
     *
     * 纯粹是语义标记：界面上两者都是同一行开关，但分组总开关关掉后，
     * 其下所有子项的 Hook 回调都会因为 `Prefs.xxxEnabled` 为 false 而直接放行。
     */
    val isGroupRoot: Boolean = false
)

object Features {

    /** 全部功能声明。组内顺序即界面顺序。 */
    val ALL: List<FeatureSpec> = listOf(
        // ---------------- 多账号 ----------------
        FeatureSpec(
            key = Prefs.ENABLE_ACCOUNT,
            group = FeatureGroup.ACCOUNT,
            title = "启用多账号上限提升",
            summary = "解锁 Telegram 原生限制：免费版 3 个、会员版 5 个账号。",
            risk = RiskLevel.MEDIUM,
            riskMessage = "该功能会扩容 Telegram 内部的账号实例数组。\n\n" +
                "在少数版本上，超出原生上限的账号可能出现同步异常或不稳定。\n" +
                "建议先用默认值 6 观察一段时间，确认稳定后再继续上调。",
            isGroupRoot = true
        ),

        // ---------------- 界面与主题 ----------------
        FeatureSpec(
            key = Prefs.ENABLE_UI,
            group = FeatureGroup.UI,
            title = "启用界面定制",
            summary = "替换内嵌字体、收起 Stories 入口等界面层面的调整。",
            isGroupRoot = true
        ),
        FeatureSpec(
            key = Prefs.SYSTEM_FONT,
            group = FeatureGroup.UI,
            title = "使用系统字体",
            summary = "把 Telegram 内嵌的 Roboto 字体替换为系统字体，代码块仍保留等宽。"
        ),
        FeatureSpec(
            key = Prefs.HIDE_STORIES,
            group = FeatureGroup.UI,
            title = "隐藏 Stories",
            summary = "收起聊天列表顶部的 Stories 环与相关入口。"
        ),

        // ---------------- 网络 ----------------
        FeatureSpec(
            key = Prefs.ENABLE_NET,
            group = FeatureGroup.NETWORK,
            title = "启用网络增强",
            summary = "连接与代理相关的行为调整。",
            isGroupRoot = true
        ),
        FeatureSpec(
            key = Prefs.BLOCK_PROXY_PROBE,
            group = FeatureGroup.NETWORK,
            title = "阻止代理连通性探测",
            summary = "添加代理前 Telegram 会先发一次不走代理的探测请求，存在暴露真实出口 IP 的可能。开启后跳过。",
            risk = RiskLevel.MEDIUM,
            riskMessage = "开启后，设置里的「检查代理」将不再返回延迟测速结果 —— " +
                "这是为了不再发起那次会暴露真实 IP 的探测。\n\n" +
                "代理本身仍可正常使用，只是不再预先测速。"
        ),
        FeatureSpec(
            key = Prefs.BLOCK_AUTO_DOWNLOAD,
            group = FeatureGroup.NETWORK,
            title = "阻止媒体自动下载",
            summary = "收到的图片、视频、文件不再自动下载，只在手动点击时才下 —— 省流量。"
        ),

        // ---------------- 隐私与本地增强 ----------------
        FeatureSpec(
            key = Prefs.ENABLE_PRIVACY,
            group = FeatureGroup.PRIVACY,
            title = "启用隐私增强",
            summary = "影响消息收发状态的本地行为调整。",
            isGroupRoot = true
        ),
        FeatureSpec(
            key = Prefs.HIDE_TYPING,
            group = FeatureGroup.PRIVACY,
            title = "隐藏「正在输入 / 录音中」",
            summary = "不再向对方发送你的输入、录音、上传等实时状态。",
            risk = RiskLevel.MEDIUM,
            riskMessage = "开启后对方将完全看不到你正在输入或录音。\n\n" +
                "这会影响对方的沟通预期，请自行判断是否适合长期开启。"
        ),
        FeatureSpec(
            key = Prefs.ANTI_RECALL,
            group = FeatureGroup.PRIVACY,
            title = "防撤回",
            summary = "对方撤回消息时拦截该删除请求，消息保留在你的聊天记录中。",
            risk = RiskLevel.HIGH,
            riskMessage = "开启前请先确认两点：\n\n" +
                "1. 副作用：你自己发起的「为所有人删除」也可能被拦下，也就是删不掉已发出的消息。\n" +
                "2. 合规：保留他人撤回的内容可能涉及隐私与取证合规问题，请仅用于个人设备上的正当用途。\n\n" +
                "确认已理解并愿意承担上述影响？"
        ),
        FeatureSpec(
            key = Prefs.BLOCK_READ_RECEIPT,
            group = FeatureGroup.PRIVACY,
            title = "不上报已读回执",
            summary = "读完消息不向服务器发送已读位置，对方看不到你的已读状态。",
            risk = RiskLevel.HIGH,
            riskMessage = "开启后，对方永远看不到你读过消息（群里的已读人数也不会增加）。\n\n" +
                "需要注意：\n" +
                "1. 服务器侧仍认为这些消息未读，换设备或重新登录时可能重新出现未读标记。\n" +
                "2. 频道/群组的未读计数会在服务器侧累积。\n\n" +
                "本地依然照常标记为已读，不影响你自己看消息。\n\n" +
                "确认开启？"
        ),
        FeatureSpec(
            key = Prefs.HIDE_ONLINE,
            group = FeatureGroup.PRIVACY,
            title = "隐藏在线状态",
            summary = "不向服务器上报「我在线」，对方看到你一直是离线状态。",
            risk = RiskLevel.MEDIUM,
            riskMessage = "开启后，任何人（含联系人、群成员）都看不到你在线，" +
                "只会看到「最后上线」停在你开启这项功能之前的某个时间点。\n\n" +
                "这会影响别人对你的回复预期 —— 对方可能以为你一直没看手机。\n\n" +
                "确认开启？"
        ),

        // ---------------- 诊断 ----------------
        FeatureSpec(
            key = Prefs.ENABLE_DIAG,
            group = FeatureGroup.DIAG,
            title = "输出诊断日志",
            summary = "启动时探测关键 Hook 点是否命中，结果写入 LSPosed 日志，便于适配新版本。",
            default = true,
            isGroupRoot = true
        ),
    )

    /** 按分组切好、顺序不变。 */
    val byGroup: Map<FeatureGroup, List<FeatureSpec>> = ALL.groupBy { it.group }

    fun find(key: String): FeatureSpec? = ALL.firstOrNull { it.key == key }

    /**
     * 「关闭全部功能」覆盖的 key：除诊断组以外的全部开关。
     *
     * 诊断日志是排查问题用的，不该被一键关闭 —— 否则用户关完所有功能后
     * 遇到问题再来反馈，日志里什么都看不到。
     */
    val disableAllKeys: List<String> =
        ALL.filter { it.group != FeatureGroup.DIAG }.map { it.key }

    /**
     * 带副作用的功能：key -> 标题。
     *
     * 导入配置时逐项确认用；否则贴一段文本就能绕过「开启风险功能需确认」。
     */
    val riskyTitles: Map<String, String> =
        ALL.filter { it.risk != RiskLevel.NONE }.associate { it.key to it.title }

    /** 分组总开关的 key 列表，用于启动日志里打印配置快照。 */
    val groupRootKeys: List<String> = ALL.filter { it.isGroupRoot }.map { it.key }
}
