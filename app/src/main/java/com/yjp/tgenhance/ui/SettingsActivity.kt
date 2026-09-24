package com.yjp.tgenhance.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.R

/**
 * 设置界面。
 *
 * 全部用代码构建，不依赖 XML 布局 —— 模块本身很轻，这样能减少资源耦合，
 * 也避免 LSPosed 在部分 ROM 上解析资源时出问题。
 *
 * 风险项处理：带风险的开关在**开启时**弹出说明并要求确认，而不是静默生效。
 * 用户确认后才写入配置；取消则把开关状态回滚。
 */
class SettingsActivity : Activity() {

    private enum class Risk { NONE, MEDIUM, HIGH }

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.initForApp(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll.addView(root)
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
        root.addView(textView("TG 增强", 24f, R.color.text_primary).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(4))
        })
        root.addView(
            textView("针对 Telegram 客户端的本地功能增强。修改后需重启 Telegram 生效。", 13f, R.color.text_secondary).apply {
                setPadding(dp(4), 0, 0, dp(16))
            }
        )
    }

    private fun buildAccountSection() {
        val card = sectionCard("多账号")
        groupSwitch(
            card,
            groupKey = Prefs.ENABLE_ACCOUNT,
            title = "启用多账号上限提升",
            summary = "解锁 Telegram 原生限制：免费版 3 个、会员版 5 个账号；开启后可按下方数值提升。",
            risk = Risk.MEDIUM,
            riskMessage = "该功能会扩容 Telegram 内部的账号实例数组。\n\n" +
                "在少数版本上，超出原生上限的账号可能出现同步异常或不稳定。\n" +
                "建议先用默认值 6 观察一段时间，确认稳定后再继续上调。"
        )
        intRow(
            card,
            key = Prefs.MAX_ACCOUNTS,
            title = "最大账号数",
            summary = "可设置 3 – 16 个账号。",
            min = Prefs.MIN_ACCOUNTS_LIMIT,
            max = Prefs.MAX_ACCOUNTS_LIMIT,
            default = Prefs.DEF_MAX_ACCOUNTS
        )
    }

    private fun buildUiSection() {
        val card = sectionCard("界面与主题")
        groupSwitch(
            card,
            groupKey = Prefs.ENABLE_UI,
            title = "启用界面定制",
            summary = "替换内嵌字体、收起 Stories 入口等界面层面的调整。",
            risk = Risk.NONE
        )
        toggleRow(
            card,
            key = Prefs.SYSTEM_FONT,
            title = "使用系统字体",
            summary = "把 Telegram 内嵌的 Roboto 字体替换为系统字体，代码块仍保留等宽。",
            risk = Risk.NONE
        )
        toggleRow(
            card,
            key = Prefs.HIDE_STORIES,
            title = "隐藏 Stories",
            summary = "收起聊天列表顶部的 Stories 环与相关入口。",
            risk = Risk.NONE
        )
    }

    private fun buildNetworkSection() {
        val card = sectionCard("网络")
        groupSwitch(
            card,
            groupKey = Prefs.ENABLE_NET,
            title = "启用网络增强",
            summary = "连接与代理相关的行为调整。",
            risk = Risk.NONE
        )
        toggleRow(
            card,
            key = Prefs.BLOCK_PROXY_PROBE,
            title = "阻止代理连通性探测",
            summary = "Telegram 添加代理前会先发一次不走代理的探测请求，可被用于套取真实出口 IP。开启后跳过该探测。",
            risk = Risk.MEDIUM,
            riskMessage = "开启后，设置里的「检查代理」将不再返回延迟测速结果 —— " +
                "这是为了不再发起那次会暴露真实 IP 的探测。\n\n" +
                "代理本身仍可正常使用，只是不再预先测速。"
        )
    }

    private fun buildPrivacySection() {
        val card = sectionCard("隐私与本地增强")
        groupSwitch(
            card,
            groupKey = Prefs.ENABLE_PRIVACY,
            title = "启用隐私增强",
            summary = "影响消息收发状态的本地行为调整。",
            risk = Risk.NONE
        )
        toggleRow(
            card,
            key = Prefs.HIDE_TYPING,
            title = "隐藏「正在输入 / 录音中」",
            summary = "不再向对方发送你的输入、录音、上传等实时状态。",
            risk = Risk.MEDIUM,
            riskMessage = "开启后对方将完全看不到你正在输入或录音。\n\n" +
                "这会影响对方的沟通预期，请自行判断是否适合长期开启。"
        )
        toggleRow(
            card,
            key = Prefs.ANTI_RECALL,
            title = "防撤回",
            summary = "对方撤回消息时，拦截该删除请求，消息保留在你的聊天记录中。",
            risk = Risk.HIGH,
            riskMessage = "请务必了解以下两点后再开启：\n\n" +
                "1. 副作用：你自己发起的「为所有人删除」同样会被拦下，也就是你删不掉已发出的消息。\n" +
                "2. 合规：保留他人撤回的内容可能涉及隐私与取证合规问题，请仅用于个人设备上的正当用途。\n\n" +
                "确认已理解并愿意承担上述影响？"
        )
    }

    private fun buildDiagSection() {
        val card = sectionCard("诊断")
        toggleRow(
            card,
            key = Prefs.ENABLE_DIAG,
            title = "输出诊断日志",
            summary = "在 Telegram 启动时探测关键 Hook 点是否命中，结果写入 LSPosed 日志，便于适配新版本。",
            risk = Risk.NONE,
            default = true
        )
    }

    private fun buildFooter() {
        root.addView(
            textView(
                "查看日志：LSPosed 管理器 → 日志，搜索关键字 TgEnhance 即可看到诊断报告。\n" +
                    "修改任何设置后，请从最近任务划掉 Telegram 再重新打开。",
                12f,
                R.color.text_secondary
            ).apply { setPadding(dp(8), dp(16), dp(8), 0) }
        )
    }

    // ------------------------------------------------------------------
    // 组件构建
    // ------------------------------------------------------------------

    private fun sectionCard(title: String): LinearLayout {
        root.addView(
            textView(title, 13f, R.color.accent).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(16), 0, dp(8))
            }
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card))
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return card
    }

    /** 分组总开关。 */
    private fun groupSwitch(
        parent: LinearLayout,
        groupKey: String,
        title: String,
        summary: String,
        risk: Risk,
        riskMessage: String? = null
    ) {
        val sw = addSwitchRow(parent, title, summary, prefs.getBoolean(groupKey, false))
        bindSwitch(sw, groupKey, risk, riskMessage, title)
    }

    /** 子项开关。 */
    private fun toggleRow(
        parent: LinearLayout,
        key: String,
        title: String,
        summary: String,
        risk: Risk,
        riskMessage: String? = null,
        default: Boolean = false
    ) {
        val sw = addSwitchRow(parent, title, summary, prefs.getBoolean(key, default))
        bindSwitch(sw, key, risk, riskMessage, title)
    }

    private fun bindSwitch(
        sw: Switch,
        key: String,
        risk: Risk,
        riskMessage: String?,
        title: String
    ) {
        sw.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && risk != Risk.NONE && !riskMessage.isNullOrBlank()) {
                confirmRisk(title, riskMessage) { accepted ->
                    if (accepted) {
                        prefs.edit().putBoolean(key, true).apply()
                    } else {
                        // 回滚，且避免再次触发监听
                        sw.setOnCheckedChangeListener(null)
                        sw.isChecked = false
                        bindSwitch(sw, key, risk, riskMessage, title)
                    }
                }
            } else {
                prefs.edit().putBoolean(key, isChecked).apply()
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

    /** 数值调节行（SeekBar）。 */
    private fun intRow(
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
            setPadding(0, dp(12), 0, dp(12))
        }

        val valueLabel = textView(current.toString(), 15f, R.color.accent).apply {
            setTypeface(typeface, Typeface.BOLD)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            textView(title, 15f, R.color.text_primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(valueLabel)

        box.addView(header)
        box.addView(
            textView(summary, 12.5f, R.color.text_secondary).apply {
                setPadding(0, dp(2), 0, dp(8))
            }
        )

        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
        }
        box.addView(seek)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + progress
                valueLabel.text = value.toString()
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        parent.addView(box)
        parent.addView(divider())
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(textView(title, 15f, R.color.text_primary))
        texts.addView(
            textView(summary, 12.5f, R.color.text_secondary).apply {
                setPadding(0, dp(2), dp(8), 0)
            }
        )

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val sw = Switch(this).apply { isChecked = checked }
        row.addView(sw)

        parent.addView(row)
        parent.addView(divider())
        return sw
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun textView(text: String, sizeSp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color(colorRes))
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val prefs get() = Prefs.requireAppPrefs()
}
