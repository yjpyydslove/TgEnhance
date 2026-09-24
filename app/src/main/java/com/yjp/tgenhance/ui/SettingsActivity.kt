package com.yjp.tgenhance.ui

import android.app.Activity
import android.app.AlertDialog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.initForApp(this)

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
        buildDiagSection()
        buildFooter()
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

    private fun buildDiagSection() {
        section(
            sectionTitle = "诊断",
            groupTitle = "输出诊断日志",
            groupSummary = "启动时探测关键 Hook 点是否命中，结果写入 LSPosed 日志，便于适配新版本。",
            groupKey = Prefs.ENABLE_DIAG,
            groupDefault = true
        ) { }
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
            TextView(this).apply {
                text = sectionTitle
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.accent))
                setPadding(dp(20), dp(24), dp(16), dp(8))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(color(R.color.card))
            }
            clipToPadding = false
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        )

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
    }
}
