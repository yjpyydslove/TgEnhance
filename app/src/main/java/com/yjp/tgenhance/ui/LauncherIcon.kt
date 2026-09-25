package com.yjp.tgenhance.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog

/**
 * 模块自身桌面图标的显示控制（v N1.1）。
 *
 * ### 为什么不是直接禁用 SettingsActivity
 *
 * 设置页同时是「桌面入口」和「设置页本体」。如果直接禁用它的 LAUNCHER 组件，
 * 组件本身会被停掉 —— 连 LSPosed 管理器的「打开」也会一起失效，
 * 用户就再也进不去设置页了。
 *
 * 所以 Manifest 里把 LAUNCHER 放在一个 [ALIAS_CLASS]（activity-alias）上，
 * 设置页本身只保留一个不带 LAUNCHER 的显式 action。
 * 这里禁用的**只有那个 alias**，设置页与显式 action 完全不受影响。
 *
 * ### 恢复途径
 *
 * 图标被隐藏后，用户仍可：
 *  1. 从 LSPosed 管理器打开（它用显式 intent 指向设置页）
 *  2. 用 `com.yjp.tgenhance.action.SETTINGS` 这个 action 唤起
 *
 * 恢复可见：只要再进设置页把开关关掉，[apply] 会重新启用 alias。
 */
object LauncherIcon {

    /** Manifest 里承载桌面图标的 alias 全名。 */
    const val ALIAS_CLASS = "com.yjp.tgenhance.ui.LauncherAlias"

    /**
     * 把图标的显示状态同步成 [Prefs.HIDE_LAUNCHER_ICON] 的值。
     *
     * 幂等：重复调用只是重复设置同一个状态，不会产生副作用。
     * 每次设置页启动时都会调一次（见 [SettingsActivity.onCreate]），
     * 这样即使状态因为换机、刷机、恢复备份而错位，也能自动纠回来 ——
     * 否则会出现「开关显示开着、图标其实还在」这种自相矛盾的状态。
     *
     * @return true 表示图标现在处于「隐藏」状态。
     */
    fun apply(context: Context): Boolean {
        val hide = try {
            Prefs.requireAppPrefs().getBoolean(Prefs.HIDE_LAUNCHER_ICON, false)
        } catch (t: Throwable) {
            false
        }

        // 先对账再动手（v N1.12 补上 —— 此前 isHiddenNow() 定义了却没人调用，
        // 注释承诺的「配置与系统状态不一致时能指出来」并没有实现）。
        //
        // 用户可能从系统设置、LSPosed 管理器或其他方式直接改过组件状态，
        // 那些操作不经过本模块，配置无从得知。不查这一次的话，
        // 界面上会出现「开关关着、图标其实已经没了」这种自相矛盾的画面，
        // 而 apply() 紧接着又把它纠回去 —— 用户看起来就是「开关失灵」。
        // 留一行日志，问题至少可查。
        val actual = isHiddenNow(context)
        if (actual != hide) {
            XLog.i(
                "[图标] 系统实际状态与配置不一致" +
                    "（配置=${if (hide) "隐藏" else "显示"}，" +
                    "实际=${if (actual) "隐藏" else "显示"}），已按配置纠正"
            )
        }

        setHidden(context, hide)
        return hide
    }

    /** 直接设置图标是否隐藏，不读配置。 */
    fun setHidden(context: Context, hidden: Boolean) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context.packageName, ALIAS_CLASS)
            val newState = if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            val current = pm.getComponentEnabledSetting(component)
            if (current == newState) return

            // DONT_KILL_APP：不重启本进程。设置页正开着，
            // 杀掉自己的进程会让用户看到闪退，也会把刚写的配置丢掉。
            pm.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            XLog.i("[图标] 桌面图标已${if (hidden) "隐藏" else "恢复"}")
        } catch (t: Throwable) {
            // 某些 ROM 会限制应用改自己的组件状态；
            // 失败时把配置改回「显示」，避免界面显示与实际不一致
            XLog.e("[图标] 桌面图标状态切换失败: ${t.message}")
            try {
                Prefs.requireAppPrefs().edit()
                    .putBoolean(Prefs.HIDE_LAUNCHER_ICON, false)
                    .apply()
            } catch (ignored: Throwable) {
                // 配置都写不进去就没什么可做的了
            }
        }
    }

    /**
     * 当前桌面图标是否真的处于隐藏状态（读系统里的实际状态，不读配置）。
     *
     * 用于自检：配置与系统状态不一致时能指出来。
     */
    fun isHiddenNow(context: Context): Boolean = try {
        val component = ComponentName(context.packageName, ALIAS_CLASS)
        context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (t: Throwable) {
        false
    }
}
