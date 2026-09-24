package com.yjp.tgenhance.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.R
import com.yjp.tgenhance.diag.DiagProtocol

/**
 * 设置界面。
 *
 * 版式对齐 Telegram 官方客户端：
 *  - 页面底色 + 16dp 圆角分组卡片（官方设置页的分组形态）
 *  - 分组标题用主色 accent，正文 16sp / 说明 13sp 灰
 *  - 分割线从文字左侧 16dp 起，颜色是 6% 前景色（不是实色灰）
 *  - 开关是自绘的 [TgSwitch]，滑块大于轨道并带投影
 *  - 深色模式下主色跟随官方 Night 主题的紫色 #8774E1
 *
 * 全部用代码构建，不依赖 XML 布局 —— 模块本身很轻，这样能减少资源耦合，
 * 也避免 LSPosed 在部分 ROM 上解析资源时出问题。
 *
 * 风险项处理：带风险的开关在**开启时**弹出说明并要求确认，而不是静默生效。
 * 用户确认后才写入配置；取消则把开关状态静默回滚。
 */
class SettingsActivity : Activity() {

    private enum class Risk { NONE, MEDIUM, HIGH }

    private lateinit var root: LinearLayout

    /** 「运行状态」卡片正文；收到 hook 端回传时直接刷新它。 */
    private var statusView: TextView? = null

    /**
     * 接收 hook 端的诊断快照。
     *
     * 用 `RECEIVER_EXPORTED` 是因为发送方是 Telegram 进程（另一个应用）；
     * 来源可信度由令牌保证 —— 令牌只存在于本模块私有配置里，别处伪造不出来。
     */
    private val diagReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DiagProtocol.ACTION) return

            val token = intent.getStringExtra(DiagProtocol.EXTRA_TOKEN)
            val expected = prefs.getString(Prefs.DIAG_TOKEN, null)
            if (token.isNullOrEmpty() || token != expected) return

            val payload = intent.getStringExtra(DiagProtocol.EXTRA_PAYLOAD) ?: return
            prefs.edit()
                .putString(Prefs.DIAG_SNAPSHOT, payload)
                .putLong(Prefs.DIAG_SNAPSHOT_AT, System.currentTimeMillis())
                .apply()
            statusView?.text = renderSnapshot(payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.initForApp(this)
        // 回传令牌必须先存在，hook 端才有东西可带
        Prefs.ensureDiagToken()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        root = content

        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(scroll)

        buildHeader()
        buildAccountSection()
        buildUiSection()
        buildNetworkSection()
        buildPrivacySection()
        buildPresetSection()
        buildDiagSection()
        buildStatusSection()
        buildFooter()
    }

    override fun onResume() {
        super.onResume()
        try {
            registerReceiver(diagReceiver, IntentFilter(DiagProtocol.ACTION), Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            // 注册失败不影响其余功能
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(diagReceiver)
        } catch (t: Throwable) {
            // 未注册时忽略
        }
    }

    // ------------------------------------------------------------------
    // 各分组
    // ------------------------------------------------------------------

    private fun buildHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(4))
        }

        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.accent))
            }
        }
        badge.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_plane)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        )
        header.addView(badge, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(color(R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        texts.addView(
            TextView(this).apply {
                text = "Telegram 本地功能增强"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            }
        )
        header.addView(
            texts,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
        )

        root.addView(header)
        root.addView(
            TextView(this).apply {
                text = getString(R.string.restart_tip)
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(16), dp(14), dp(16), 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
    }

    private fun buildAccountSection() {
        section(
            sectionTitle = "多账号",
            groupTitle = "启用多账号上限提升",
            groupSummary = "解锁 Telegram 原生限制：免费版 3 个、会员版 5 个账号。",
            groupKey = Prefs.ENABLE_ACCOUNT,
            groupRisk = Risk.MEDIUM,
            groupRiskMessage = "该功能会扩容 Telegram 内部的账号实例数组。\n\n" +
                "在少数版本上，超出原生上限的账号可能出现同步异常或不稳定。\n" +
                "建议先用默认值 6 观察一段时间，确认稳定后再继续上调。"
        ) { card ->
            sliderRow(
                card,
                key = Prefs.MAX_ACCOUNTS,
                title = "最大账号数",
                summary = "可设置 3 – 16 个账号，默认 6。",
                min = Prefs.MIN_ACCOUNTS_LIMIT,
                max = Prefs.MAX_ACCOUNTS_LIMIT,
                default = Prefs.DEF_MAX_ACCOUNTS
            )
        }
    }

    private fun buildUiSection() {
        section(
            sectionTitle = "界面与主题",
            groupTitle = "启用界面定制",
            groupSummary = "替换内嵌字体、收起 Stories 入口等界面层面的调整。",
            groupKey = Prefs.ENABLE_UI
        ) { card ->
            switchRow(
                card,
                key = Prefs.SYSTEM_FONT,
                title = "使用系统字体",
                summary = "把 Telegram 内嵌的 Roboto 字体替换为系统字体，代码块仍保留等宽。"
            )
            switchRow(
                card,
                key = Prefs.HIDE_STORIES,
                title = "隐藏 Stories",
                summary = "收起聊天列表顶部的 Stories 环与相关入口。"
            )
        }
    }

    private fun buildNetworkSection() {
        section(
            sectionTitle = "网络",
            groupTitle = "启用网络增强",
            groupSummary = "连接与代理相关的行为调整。",
            groupKey = Prefs.ENABLE_NET
        ) { card ->
            switchRow(
                card,
                key = Prefs.BLOCK_PROXY_PROBE,
                title = "阻止代理连通性探测",
                summary = "添加代理前 Telegram 会先发一次不走代理的探测请求，存在暴露真实出口 IP 的可能。开启后跳过。",
                risk = Risk.MEDIUM,
                riskMessage = "开启后，设置里的「检查代理」将不再返回延迟测速结果 —— " +
                    "这是为了不再发起那次会暴露真实 IP 的探测。\n\n" +
                    "代理本身仍可正常使用，只是不再预先测速。"
            )
        }
    }

    private fun buildPrivacySection() {
        section(
            sectionTitle = "隐私与本地增强",
            groupTitle = "启用隐私增强",
            groupSummary = "影响消息收发状态的本地行为调整。",
            groupKey = Prefs.ENABLE_PRIVACY
        ) { card ->
            switchRow(
                card,
                key = Prefs.HIDE_TYPING,
                title = "隐藏「正在输入 / 录音中」",
                summary = "不再向对方发送你的输入、录音、上传等实时状态。",
                risk = Risk.MEDIUM,
                riskMessage = "开启后对方将完全看不到你正在输入或录音。\n\n" +
                    "这会影响对方的沟通预期，请自行判断是否适合长期开启。"
            )
            switchRow(
                card,
                key = Prefs.ANTI_RECALL,
                title = "防撤回",
                summary = "对方撤回消息时拦截该删除请求，消息保留在你的聊天记录中。",
                risk = Risk.HIGH,
                riskMessage = "开启前请先确认两点：\n\n" +
                    "1. 副作用：你自己发起的「为所有人删除」也可能被拦下，也就是删不掉已发出的消息。\n" +
                    "2. 合规：保留他人撤回的内容可能涉及隐私与取证合规问题，请仅用于个人设备上的正当用途。\n\n" +
                    "确认已理解并愿意承担上述影响？"
            )
            switchRow(
                card,
                key = Prefs.BLOCK_READ_RECEIPT,
                title = "不上报已读回执",
                summary = "读完消息不向服务器发送已读位置，对方看不到你的已读状态。",
                risk = Risk.HIGH,
                riskMessage = "开启后，对方永远看不到你读过消息（群里的已读人数也不会增加）。\n\n" +
                    "需要注意：\n" +
                    "1. 服务器侧仍认为这些消息未读，换设备或重新登录时可能重新出现未读标记。\n" +
                    "2. 频道/群组的未读计数会在服务器侧累积。\n\n" +
                    "本地依然照常标记为已读，不影响你自己看消息。\n\n" +
                    "确认开启？"
            )
        }
    }

    /**
     * 「快捷配置」卡片。
     *
     * 预设刻意只组合**低风险**项：带副作用的开关（防撤回、隐藏输入状态、
     * 不上报已读）一律不放进预设，必须由用户单独开启并确认 ——
     * 一键把有代价的功能全打开，不符合本模块的风险提示原则。
     */
    private fun buildPresetSection() {
        root.addView(
            sectionTitleView("快捷配置"),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        val card = cardView()
        root.addView(card, cardParams())

        actionRow(card, "界面清爽：系统字体 + 隐藏 Stories") {
            applyPreset(
                "界面清爽",
                listOf(
                    Prefs.ENABLE_UI to true,
                    Prefs.SYSTEM_FONT to true,
                    Prefs.HIDE_STORIES to true
                )
            )
        }

        actionRow(card, "网络防护：阻止代理连通性探测") {
            applyPreset(
                "网络防护",
                listOf(
                    Prefs.ENABLE_NET to true,
                    Prefs.BLOCK_PROXY_PROBE to true
                )
            )
        }

        actionRow(card, "多账号：启用并设为 6 个") {
            applyPreset(
                "多账号",
                listOf(
                    Prefs.ENABLE_ACCOUNT to true,
                    Prefs.MAX_ACCOUNTS to Prefs.DEF_MAX_ACCOUNTS
                )
            )
        }

        actionRow(card, "导出配置到剪贴板") { exportToClipboard() }
        actionRow(card, "从剪贴板导入配置") { importFromClipboard() }
        actionRow(card, "关闭全部功能", danger = true) { confirmDisableAll() }

        trimTrailingDivider(card)
    }

    private fun buildDiagSection() {
        section(
            sectionTitle = "诊断",
            groupTitle = "输出诊断日志",
            groupSummary = "启动时探测关键 Hook 点是否命中，结果写入 LSPosed 日志，便于适配新版本。",
            groupKey = Prefs.ENABLE_DIAG,
            groupDefault = true
        ) { }
    }

    /**
     * 「运行状态」卡片。
     *
     * 把 hook 端回传的触发计数直接摊在界面上 —— 在此之前，要判断某个功能
     * 是否真的生效，只能离开应用去翻 LSPosed 日志，普通用户根本不会做，
     * 于是「功能静默失效」这件事长期无人察觉。
     */
    private fun buildStatusSection() {
        root.addView(
            sectionTitleView("运行状态"),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        val card = cardView()
        root.addView(card, cardParams())

        val body = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            text = renderSnapshot(prefs.getString(Prefs.DIAG_SNAPSHOT, null))
        }
        card.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        statusView = body
    }

    /**
     * 渲染 hook 端回传的快照。
     *
     * 载荷是朴素文本，被 [DiagProtocol.SECTION_SELFCHECK] 切成两段：
     * 前半是 hook 触发计数，后半是挂载期自检清单。
     */
    private fun renderSnapshot(payload: String?): CharSequence {
        if (payload.isNullOrBlank()) {
            return "尚未收到运行报告。\n\n" +
                "1. 先打开一次 Telegram，让模块完成挂载\n" +
                "2. 再切回本页面（首次可能要多等几秒）\n\n" +
                "始终没有数据时，请检查 LSPosed 里本模块是否已启用、" +
                "「作用域」是否勾选了 Telegram。"
        }

        val sections = payload.split(DiagProtocol.SECTION_SELFCHECK)
        return buildString {
            append(renderHookSection(sections.getOrNull(0).orEmpty()))
            append("\n\n")
            append(renderSelfCheckSection(sections.getOrNull(1).orEmpty()))
        }
    }

    private fun renderHookSection(section: String): String {
        val timePrefix = DiagProtocol.KEY_TIME + "="
        val lines = section.trim().lines()
        val reportTime = lines.firstOrNull { it.startsWith(timePrefix) }
            ?.substringAfter('=')
            ?: "--:--:--"

        val items = lines
            .filter { it.contains('=') && !it.startsWith(timePrefix) }
            .map { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 0) }

        if (items.isEmpty()) {
            return "报告时间 $reportTime\n\n" +
                "报告里没有任何 hook 点 —— 模块可能没有真正挂载到 Telegram 进程。"
        }

        val sb = StringBuilder()
        sb.append("报告时间 ").append(reportTime).append('\n')
        sb.append("（Telegram 每次切换前后台都会刷新）\n\n")

        var active = 0
        var idle = 0
        for ((name, count) in items) {
            val label = HOOK_LABELS[name] ?: name
            if (count > 0) {
                active++
                sb.append("● ").append(label).append("  已触发 ").append(count).append(" 次\n")
            } else {
                idle++
                sb.append("○ ").append(label).append("  未触发\n")
            }
        }

        sb.append("\n本次会话生效 ").append(active).append(" 项，未触发 ").append(idle).append(" 项。")
        sb.append("\n\n「未触发」只代表这段时间没出现过对应动作（例如没人撤回消息），")
        sb.append("不等于功能失效；完全没触发过的项才需要怀疑 Hook 点随版本变动。")
        return sb.toString()
    }

    /** 渲染挂载期自检：命中多少、哪些没匹配上。 */
    private fun renderSelfCheckSection(section: String): String {
        val lines = section.trim().lines().filter { it.isNotBlank() }

        lines.firstOrNull { it.startsWith("#") }?.let {
            return "Hook 点自检：" + it.removePrefix("#").trim()
        }
        if (lines.isEmpty()) {
            return "Hook 点自检：本次会话未采集到结果"
        }

        val hit = lines.count { it.startsWith(DiagProtocol.MARK_OK) }
        val missing = lines.filter { it.startsWith(DiagProtocol.MARK_MISS) }

        val sb = StringBuilder()
        sb.append("Hook 点自检：命中 ").append(hit).append('/').append(lines.size)
        if (missing.isEmpty()) {
            sb.append("\n\n全部命中 —— 目标类与方法均匹配当前 Telegram 版本。")
            return sb.toString()
        }

        sb.append("\n\n未匹配的项（对应功能大概率无效，需要针对该版本重新定位）：\n")
        for (line in missing) {
            sb.append("  · ").append(line.removePrefix(DiagProtocol.MARK_MISS)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun buildFooter() {
        root.addView(
            TextView(this).apply {
                text = "查看日志：LSPosed 管理器 → 日志，搜索关键字 TgEnhance，" +
                    "即可看到「诊断报告」与「Hook 触发统计」两段信息。"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(16), dp(20), dp(16), 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        root.addView(
            dangerButton("重置所有设置") { confirmReset() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(20)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "TgEnhance ${appVersionName()}"
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
        )
    }

    // ------------------------------------------------------------------
    // 快捷配置
    // ------------------------------------------------------------------

    /** 卡片内的可点行：TG 风格的做法是主色文字、整行可点、带水波纹。 */
    private fun actionRow(
        parent: LinearLayout,
        title: String,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        val row = TextView(this).apply {
            text = title
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(color(if (danger) R.color.danger else R.color.accent))
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), null, null)
            setOnClickListener { onClick() }
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        parent.addDivider()
    }

    private fun applyPreset(name: String, entries: List<Pair<String, Any>>) {
        val editor = prefs.edit()
        for ((key, value) in entries) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        editor.apply()
        toast("已应用：$name")
        recreate()
    }

    private fun confirmDisableAll() {
        AlertDialog.Builder(this)
            .setTitle("关闭全部功能")
            .setMessage("将关闭所有增强开关（诊断日志除外），配置项本身保留。\n\n随时可以再打开。")
            .setPositiveButton("全部关闭") { _, _ ->
                val editor = prefs.edit()
                for (key in ALL_FEATURE_KEYS) editor.putBoolean(key, false)
                editor.apply()
                toast("已关闭全部功能")
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportToClipboard() {
        val text = Prefs.exportFrom(prefs)
        if (!copyToClipboard(text)) {
            toast("复制失败，请检查剪贴板权限")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("配置已复制")
            .setMessage("已复制到剪贴板，粘贴出来即可保存，或传到另一台设备导入。\n\n$text")
            .setPositiveButton("好", null)
            .show()
    }

    /**
     * 从剪贴板导入配置。
     *
     * 注意：**导入同样要过风险确认**。否则用户贴一段文本就能绕过
     * 「开启风险功能需确认」的约束，前面所有风险提示等于白做。
     */
    private fun importFromClipboard() {
        val raw = readClipboard()
        if (raw.isNullOrBlank()) {
            toast("剪贴板为空")
            return
        }

        val risky = RISKY_KEYS.filterKeys { raw.contains("$it=1") }.values.toList()
        if (risky.isEmpty()) {
            doImport(raw)
            return
        }

        confirmRisk(
            "导入的配置包含风险功能",
            "这段配置里开启了以下带副作用的功能：\n\n" +
                risky.joinToString("\n") { "· $it" } + "\n\n" +
                "各功能的具体影响请见上方对应开关的说明。确认按这段配置导入？"
        ) { accepted ->
            if (accepted) doImport(raw)
        }
    }

    private fun doImport(raw: String) {
        val applied = Prefs.importTo(prefs, raw)
        if (applied < 0) {
            AlertDialog.Builder(this)
                .setTitle("导入失败")
                .setMessage(
                    "剪贴板里的内容不是本模块导出的配置格式。\n\n" +
                        "请确认复制的是「导出配置到剪贴板」得到的那段文本。"
                )
                .setPositiveButton("好", null)
                .show()
            return
        }
        toast("已导入 $applied 项")
        recreate()
    }

    private fun copyToClipboard(text: String): Boolean = try {
        val cm = getSystemService(ClipboardManager::class.java)
        if (cm == null) false else {
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            true
        }
    } catch (t: Throwable) {
        false
    }

    private fun readClipboard(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("重置所有设置")
            .setMessage("将把本模块的全部配置恢复为默认值。\n\n只影响本模块，不触碰 Telegram 自身数据。")
            .setPositiveButton("重置") { _, _ ->
                prefs.edit().clear().apply()
                Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }

    // ------------------------------------------------------------------
    // 分组骨架
    // ------------------------------------------------------------------

    /**
     * 构建一个 TG 风格分组：分组标题 + 圆角卡片（首行是分组总开关）+ 若干子项。
     */
    private fun section(
        sectionTitle: String,
        groupTitle: String,
        groupSummary: String,
        groupKey: String,
        groupRisk: Risk = Risk.NONE,
        groupRiskMessage: String? = null,
        groupDefault: Boolean = false,
        content: (LinearLayout) -> Unit
    ) {
        root.addView(
            sectionTitleView(sectionTitle),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        val card = cardView()
        root.addView(card, cardParams())

        switchRow(
            card,
            key = groupKey,
            title = groupTitle,
            summary = groupSummary,
            risk = groupRisk,
            riskMessage = groupRiskMessage,
            default = groupDefault
        )
        content(card)
        trimTrailingDivider(card)
    }

    /** 删掉卡片最后一个子项后的多余分割线（TG 分组末尾没有线）。 */
    private fun trimTrailingDivider(card: LinearLayout) {
        if (card.childCount == 0) return
        val last = card.getChildAt(card.childCount - 1)
        if (last.tag == TAG_DIVIDER) card.removeViewAt(card.childCount - 1)
    }

    // ------------------------------------------------------------------
    // 行
    // ------------------------------------------------------------------

    private fun switchRow(
        parent: LinearLayout,
        key: String,
        title: String,
        summary: String,
        risk: Risk = Risk.NONE,
        riskMessage: String? = null,
        default: Boolean = false
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            minimumHeight = dp(56)
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(rowTitle(title))
        if (summary.isNotEmpty()) texts.addView(rowSummary(summary))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val sw = TgSwitch(this).apply { isChecked = prefs.getBoolean(key, default) }
        row.addView(
            sw,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) }
        )
        bindToggle(sw, key, risk, riskMessage, title)

        parent.addView(row)
        parent.addDivider()
    }

    private fun sliderRow(
        parent: LinearLayout,
        key: String,
        title: String,
        summary: String,
        min: Int,
        max: Int,
        default: Int
    ) {
        val current = prefs.getInt(key, default).coerceIn(min, max)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        val valueLabel = TextView(this).apply {
            text = current.toString()
            textSize = 16f
            setTextColor(color(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(rowTitle(title), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(valueLabel)
        box.addView(header)
        box.addView(rowSummary(summary))

        val bar = tgSeekBar().apply {
            this.max = max - min
            progress = current - min
        }
        box.addView(
            bar,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(26)).apply { topMargin = dp(8) }
        )

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + progress
                valueLabel.text = value.toString()
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        parent.addView(box)
        parent.addDivider()
    }

    /** 绑定开关与配置项；带风险的项在开启前弹确认，取消则静默回滚。 */
    private fun bindToggle(
        sw: TgSwitch,
        key: String,
        risk: Risk,
        riskMessage: String?,
        title: String
    ) {
        sw.onCheckedChangeListener = { checked ->
            val message = riskMessage
            if (checked && risk != Risk.NONE && !message.isNullOrBlank()) {
                confirmRisk(title, message) { accepted ->
                    if (accepted) prefs.edit().putBoolean(key, true).apply()
                    else sw.isChecked = false
                }
            } else {
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
    }

    private fun confirmRisk(title: String, message: String, onResult: (Boolean) -> Unit) {
        var decided = false
        AlertDialog.Builder(this)
            .setTitle("开启「$title」前请确认")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("我已了解，开启") { _, _ ->
                decided = true
                onResult(true)
            }
            .setNegativeButton("取消") { _, _ ->
                decided = true
                onResult(false)
            }
            .setOnDismissListener {
                if (!decided) onResult(false)
            }
            .show()
    }

    // ------------------------------------------------------------------
    // 组件构建
    // ------------------------------------------------------------------

    private fun rowTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(color(R.color.text_primary))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    /** 分组标题：TG 官方用主色小标题置于卡片上方。 */
    private fun sectionTitleView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.color.accent))
        setPadding(dp(20), dp(24), dp(16), dp(8))
    }

    /** 12dp 圆角卡片容器。 */
    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.card))
        }
        clipToPadding = false
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }

    private fun rowSummary(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(3), 0, 0)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun LinearLayout.addDivider() {
        addView(
            View(this@SettingsActivity).apply {
                tag = TAG_DIVIDER
                setBackgroundColor(color(R.color.divider))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, hairline()).apply { marginStart = dp(16) }
        )
    }

    /** 细轨道 + 圆形滑块，对齐 TG 设置页里的滑条。 */
    private fun tgSeekBar(): SeekBar {
        val trackOff = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(color(R.color.switch_off))
        }
        val trackOn = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(color(R.color.accent))
        }
        val inset = dp(9)
        val layers = LayerDrawable(arrayOf(trackOff, trackOn)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerHeight(0, dp(4))
            setLayerHeight(1, dp(4))
            setLayerGravity(0, Gravity.CENTER_VERTICAL)
            setLayerGravity(1, Gravity.CENTER_VERTICAL)
            // 两端缩进滑块半径，滑块到端点时不会被轨道边框压住
            setLayerInset(0, inset, 0, inset, 0)
            setLayerInset(1, inset, 0, inset, 0)
        }
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.accent))
            setSize(dp(18), dp(18))
        }
        return SeekBar(this).apply {
            progressDrawable = layers
            this.thumb = thumb
            thumbOffset = 0
            splitTrack = false
        }
    }

    private fun dangerButton(text: String, onClick: () -> Unit): TextView {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.card))
        }
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.danger))
            typeface = Typeface.DEFAULT_BOLD
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 1px 级别的分割线；高密度屏上 dp(1) 会变成 3px，太粗。 */
    private fun hairline(): Int =
        (resources.displayMetrics.density * 0.7f).toInt().coerceAtLeast(1)

    private val prefs get() = Prefs.requireAppPrefs()

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TAG_DIVIDER = "divider"
        const val CLIP_LABEL = "TgEnhance 配置"

        /** 「关闭全部功能」覆盖的开关（诊断日志不动）。 */
        val ALL_FEATURE_KEYS = listOf(
            Prefs.ENABLE_ACCOUNT, Prefs.ENABLE_UI, Prefs.SYSTEM_FONT, Prefs.HIDE_STORIES,
            Prefs.ENABLE_NET, Prefs.BLOCK_PROXY_PROBE,
            Prefs.ENABLE_PRIVACY, Prefs.ANTI_RECALL, Prefs.HIDE_TYPING, Prefs.BLOCK_READ_RECEIPT,
        )

        /**
         * 带副作用的功能开关。
         *
         * 导入配置时必须逐项确认 —— 否则贴一段文本就能绕过「开启风险功能需确认」，
         * 前面所有风险提示都成了摆设。
         */
        val RISKY_KEYS = mapOf(
            Prefs.ANTI_RECALL to "防撤回",
            Prefs.HIDE_TYPING to "隐藏「正在输入 / 录音中」",
            Prefs.BLOCK_READ_RECEIPT to "不上报已读回执",
            Prefs.ENABLE_ACCOUNT to "多账号上限提升",
        )

        /** hook 点 -> 用户可读名称。新增 hook 点时记得同步，否则界面会显示原始 key。 */
        val HOOK_LABELS = mapOf(
            "account.maxCount" to "账号上限查询",
            "account.expand" to "账号数组扩容",
            "ui.typeface.seen" to "字体加载请求",
            "ui.typeface.replaced" to "字体替换",
            "ui.stories" to "Stories 显示查询",
            "net.proxyProbe" to "代理连通性探测",
            "privacy.typing" to "输入状态发送",
            "privacy.deleteMessages" to "消息删除",
            "privacy.recall.blocked" to "拦截撤回",
            "privacy.readReceipt.blocked" to "拦截已读上报",
            "prefs.reload" to "配置热更新",
        )
    }
}
