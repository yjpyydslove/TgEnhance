package com.yjp.tgenhance.diag

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

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun hit(name: String) {
        try {
            val counter = counters.computeIfAbsent(name) { AtomicInteger(0) }
            // 首次触发立即记录。
            // 若只在固定时间点做一次性汇总，用户在那之前没操作就会看到计数为 0，
            // 从而把「还没触发」误判成「Hook 失效」—— 这是必须避免的误导。
            if (counter.incrementAndGet() == 1) {
                XLog.result("首次触发", name)
            }
        } catch (t: Throwable) {
            // 统计绝不能影响 hook 主流程
        }
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
            XLog.section("统计结束")
        } catch (t: Throwable) {
            XLog.e("[统计] 输出失败", t)
        }
    }
}
