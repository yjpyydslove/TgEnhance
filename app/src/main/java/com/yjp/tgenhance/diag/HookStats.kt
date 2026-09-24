package com.yjp.tgenhance.diag

import android.os.SystemClock
import com.yjp.tgenhance.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hook 触发计数。
 *
 * 诊断报告能证明「Hook 挂上了」，但不能证明「Hook 真的被调用过」。
 *
 * 版本不匹配时最隐蔽的失败形态是：类名、签名全都对得上，挂载日志一切正常，
 * 但实际调用点早已改走别的路径 —— 模块看起来工作正常，却毫无效果。
 * 这种情况下靠读代码是发现不了的，只能靠运行期计数。
 *
 * 因此给每个 hook 点挂一个计数器，启动一段时间后汇总输出：
 * **计数为 0 即可立刻判定该功能未真正生效**，无需用户描述现象。
 */
object HookStats {

    /**
     * 启动后延迟多久输出汇总（毫秒）。
     *
     * 注意：这只决定「汇总」的时机，**不影响有效性判断** ——
     * 每个 Hook 点**首次**被触发时会立刻单独记一条日志（见 [hit]），
     * 所以用户一旦操作就能立即在日志里看到反馈，不必等到汇总时间点。
     */
    const val REPORT_DELAY_MS = 90_000L

    /**
     * 单个 Hook 点触发次数的告警阈值。
     *
     * 达到之后提示一次：这类 Hook 点调用极频繁，如果用户感觉 Telegram 卡顿，
     * 优先关它对应的功能最可能见效 —— 比笼统地说「可能有点慢」有用。
     */
    private const val HIGH_FREQUENCY_THRESHOLD = 10_000

    private val startedAt = SystemClock.elapsedRealtime()

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 预登记 hook 点。
     *
     * 计数器是「首次触发时才创建」的，若不预登记，未触发的项根本不会出现在快照里，
     * 设置界面就无法区分「这个功能没生效」和「这个 hook 点没注册」。
     * 各 Hook 在挂载时调用本方法登记，快照里就会出现计数为 0 的行。
     */
    fun expect(vararg names: String) {
        try {
            for (name in names) counters.computeIfAbsent(name) { AtomicInteger(0) }
        } catch (t: Throwable) {
            // 统计绝不能影响 hook 主流程
        }
    }

    fun hit(name: String) {
        try {
            val counter = counters.computeIfAbsent(name) { AtomicInteger(0) }
            // 首次触发立即记录。
            // 若只在固定时间点做一次性汇总，用户在那之前没操作就会看到计数为 0，
            // 从而把「还没触发」误判成「Hook 失效」—— 这是必须避免的误导。
            val total = counter.incrementAndGet()
            if (total == 1) {
                XLog.result("首次触发", name)
            } else if (total == HIGH_FREQUENCY_THRESHOLD) {
                XLog.w(
                    "[统计] $name 已触发 $total 次。该 Hook 点调用极频繁，" +
                        "若感觉 Telegram 卡顿，优先关闭它对应的功能。"
                )
            }
        } catch (t: Throwable) {
            // 统计绝不能影响 hook 主流程
        }
    }

    /**
     * 导出计数快照，供设置界面回显。
     *
     * 格式刻意用最朴素的 `key=value` 逐行文本：两端一个是 Telegram 进程、
     * 一个是模块进程，走简单文本可以完全避开 JSON 序列化的版本差异。
     */
    fun snapshot(time: String): String = try {
        buildString {
            append(DiagProtocol.KEY_TIME).append('=').append(time).append('\n')
            for ((name, counter) in counters.entries.sortedBy { it.key }) {
                append(name).append('=').append(counter.get()).append('\n')
            }
        }
    } catch (t: Throwable) {
        ""
    }

    /** 快照里出现过、且真的被触发过的 hook 点数量，用于汇总展示。 */
    fun activeCount(): Int = try {
        counters.values.count { it.get() > 0 }
    } catch (t: Throwable) {
        0
    }

    fun report() {
        try {
            XLog.section("Hook 触发统计（启动后 ${REPORT_DELAY_MS / 1000} 秒）")
            val entries = counters.entries.map { it.key to it.value.get() }.sortedBy { it.first }
            if (entries.isEmpty()) {
                XLog.w("[统计] 所有 hook 点均未被触发 —— 极可能是版本不匹配导致 Hook 点失效")
                XLog.w("[统计] 请把本段日志连同上方「诊断报告」一并反馈")
                return
            }
            for ((name, count) in entries) {
                val mark = if (count == 0) "  <== 未生效" else ""
                XLog.result("统计", "$name 触发 $count 次$mark")
            }

            val uptimeSec = (SystemClock.elapsedRealtime() - startedAt) / 1000
            val frequent = entries.filter { it.second >= HIGH_FREQUENCY_THRESHOLD }.map { it.first }
            XLog.result("统计", "统计时长 ${uptimeSec}s，共 ${entries.size} 个 Hook 点")
            if (frequent.isNotEmpty()) {
                XLog.w("[统计] 高频 Hook 点：${frequent.joinToString(", ")}（卡顿时优先关对应功能）")
            }
            XLog.section("统计结束")
        } catch (t: Throwable) {
            XLog.e("[统计] 输出失败", t)
        }
    }
}
