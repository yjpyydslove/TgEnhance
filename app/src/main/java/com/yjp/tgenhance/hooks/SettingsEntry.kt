package com.yjp.tgenhance.hooks

import android.app.AndroidAppHelper
import android.content.Intent
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 在 Telegram 自己的设置页里插一个入口（v N1.4）。
 *
 * 点击后拉起模块设置界面。有了它，「隐藏模块桌面图标」就不再需要靠
 * LSPosed 管理器才能找回来 —— 日常从 Telegram 里就能进。
 *
 * ### 为什么这个功能风险高，以及怎么把它压下去
 *
 * 往别人的列表里插条目，出错方式比「功能不生效」严重得多：插错位置会打乱
 * 分组分割线，用错 viewType 会让 RecyclerView 在滚动时抛异常 ——
 * 表现是**设置页打不开或直接崩**，而这跟「增强模块」几乎联系不起来，
 * 用户根本不会往这边想。
 *
 * 所以下面每一处判断都按「拿不准就不做」写：
 *
 * 1. **只插自己的一行，绝不改动已有条目** —— 不改 id、不调顺序、不动颜色。
 * 2. **多处结构判据**：靠 viewType 与锚点 id 确认「这是设置主页、且我们看得懂」，
 *    任何一条对不上就整体放弃，只在日志里留一行。
 * 3. **全部走反射 + 探测**：`UItem` 的工厂方法、`fillItems` / `onClick`
 *    都是探测式获取，某台设备上没有就跳过，不影响其余 Hook 组。
 * 4. **重复调用保护**：`fillItems` 在设置页每次刷新都会跑，靠 id 去重，
 *    避免插入多份。
 *
 * ### 点击是怎么接住的
 *
 * `UItem` 虽然有个 `clickCallback` 字段，但 Telegram 只在少数几种 viewType
 * 下才用它（折叠箭头、搜索项的关闭按钮等），设置条目这一种**不看它**。
 * 点击真正走的是 `SettingsActivity.onClick(UItem, View, int, float, float)`，
 * 里面是个 `switch (item.id)`。
 *
 * 于是做法是：给条目一个**不会与 Telegram 自己撞车的 id**，再 hook `onClick`，
 * 在回调里按这个 id 认领。Telegram 自己的 switch 没有这个 case，会走 default
 * 什么也不做 —— 所以这里不需要「阻止原方法」，两边天然不打架。
 *
 * ### 与模块 APK 自身入口的关系
 *
 * 这个入口是**额外**加的，不是替代品。模块自己的桌面图标（或其隐藏后的
 * 显式 action）始终可用；即使这项功能在某个客户端上失效，也还有退路。
 */
object SettingsEntry {

    private const val CLS_U_ITEM = "org.telegram.ui.Components.UItem"

    /**
     * 本模块条目的 id。
     *
     * Telegram 自己用 1~23 这种小整数，且集中在 `switch` 里。
     * 这里取一个高位值，和它天然不重叠；将来它继续往上加 case 也撞不到。
     */
    private const val ENTRY_ID = 0x7E110001

    /** 取自 `UniversalAdapter`：末尾版本号视图的 viewType。 */
    private const val VIEW_TYPE_CUSTOM_SHADOW = -4

    /** 取自 `UniversalAdapter`：搜索模式下列表首项（`asSpace`）的 viewType。 */
    private const val VIEW_TYPE_SPACE = 28

    /**
     * 设置主页上「账号」那一行的 id。
     *
     * 用它当**锚点**确认拿到的是设置主页而不是别的东西：搜索模式、
     * 或将来某个 fork 换成别的列表，都不会有这一项。
     */
    private const val ANCHOR_ACCOUNT_ITEM_ID = 1

    private const val HEADER_TEXT = "TgEnhance"
    private const val ENTRY_TITLE = "模块设置"
    private const val ENTRY_SUBTITLE = "打开增强模块选项"

    /** 「锚点找不到」这件事只报一次，避免每次刷新设置页都刷一行日志。 */
    @Volatile
    private var anchorWarned = false

    fun install(classLoader: ClassLoader) {
        XLog.section("设置页入口")

        val settingsCls = ClientProfileDetector.settingsFragmentClass(classLoader)
        if (settingsCls == null) {
            XLog.w("[设置入口] 未识别到设置页类，本功能跳过（其余功能不受影响）")
            HookStatus.markUnavailable(Prefs.SETTINGS_ENTRY)
            return
        }

        val uItemCls = XposedHelpers.findClassIfExists(CLS_U_ITEM, classLoader)
        if (uItemCls == null) {
            XLog.w("[设置入口] 未找到 $CLS_U_ITEM，本功能跳过")
            HookStatus.markUnavailable(Prefs.SETTINGS_ENTRY)
            return
        }

        // asHeader(CharSequence) 与 asHeader(int, CharSequence) 同名，
        // 靠参数个数挑出只要文本的那个
        val asHeader = findFactory(uItemCls, "asHeader", 1)
        // asSettingsCell 有三个重载，取带副标题的四参版本
        val asSettingsCell = findFactory(uItemCls, "asSettingsCell", 4)

        if (asHeader == null || asSettingsCell == null) {
            XLog.w(
                "[设置入口] 该客户端的 UItem 缺少 asHeader / asSettingsCell " +
                    "（asHeader=${asHeader != null}, asSettingsCell=${asSettingsCell != null}），" +
                    "本功能跳过"
            )
            HookStatus.markUnavailable(Prefs.SETTINGS_ENTRY)
            return
        }

        val fillHooked = HookInstaller.hookAllByName(
            settingsCls, "fillItems", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("设置页入口") { inject(param, asHeader, asSettingsCell) }
                }
            }
        )
        val clickHooked = HookInstaller.hookAllByName(
            settingsCls, "onClick", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guard("设置页入口点击") { dispatch(param) }
                }
            }
        )

        if (fillHooked == 0 || clickHooked == 0) {
            XLog.w(
                "[设置入口] 挂载不完整（fillItems=$fillHooked, onClick=$clickHooked），" +
                    "入口可能不出现或点了没反应"
            )
        }
        XLog.result(
            "设置入口",
            "已接管 ${settingsCls.simpleName}：fillItems $fillHooked 处、onClick $clickHooked 处"
        )
    }

    // ------------------------------------------------------------------
    // 插入
    // ------------------------------------------------------------------

    /**
     * 在 `fillItems` 返回后，往列表里补两行：一个分组标题 + 一个设置条目。
     *
     * 插在**末尾那个版本号视图之前** —— 那里是设置页最下面的结尾，
     * 放这既不打断中间的分组，也不需要理解每个分组各自的颜色搭配。
     */
    @Suppress("UNCHECKED_CAST")
    private fun inject(param: MethodHookParam, asHeader: Method, asSettingsCell: Method) {
        if (!Prefs.settingsEntry) return

        val items = param.args.getOrNull(0) as? ArrayList<Any?> ?: return
        if (items.isEmpty()) return

        // 搜索模式：列表里装的是搜索结果，首项是 asSpace 撑开的高度。
        // 这时候插图只会让它出现在搜索结果中间，必须跳过。
        if (viewTypeOf(items[0]) == VIEW_TYPE_SPACE) return

        // fillItems 每次刷新都会跑，先看看是不是已经插过了
        if (items.any { idOf(it) == ENTRY_ID }) return

        // 锚点判据：设置主页一定有一行 id=1 的「账号」。
        // 找不到就说明这个客户端的结构和我们预期不同，整体放弃 ——
        // 入口不出现只是少个便利，插错列表却是设置页直接打不开。
        if (items.none { idOf(it) == ANCHOR_ACCOUNT_ITEM_ID }) {
            if (!anchorWarned) {
                anchorWarned = true
                XLog.w("[设置入口] 未找到设置主页锚点项，本次不插入（入口不可用，其余功能正常）")
            }
            return
        }

        val header = asHeader.invoke(null, HEADER_TEXT) ?: return
        val cell = asSettingsCell.invoke(
            null, ENTRY_ID, 0, ENTRY_TITLE, ENTRY_SUBTITLE
        ) ?: return

        val shadowIndex = items.indexOfLast { viewTypeOf(it) == VIEW_TYPE_CUSTOM_SHADOW }
        if (shadowIndex >= 0) {
            items.add(shadowIndex, header)
            items.add(shadowIndex + 1, cell)
        } else {
            items.add(header)
            items.add(cell)
        }

        HookStats.hit("ui.settingsEntry")
        XLog.result("设置入口", "已在 Telegram 设置页插入「$ENTRY_TITLE」（共 ${items.size} 项）")
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /**
     * 认领点击。
     *
     * 这里在 `afterHookedMethod` 里做，而不是 `before` + 阻断：
     * Telegram 的 `onClick` 是个 `switch (item.id)`，我们那个 id 没有对应 case，
     * 它本来就会什么都不做。既然原方法无害，就不必去拦它 ——
     * 少一次对宿主逻辑的干预，就少一分跑偏的可能。
     */
    private fun dispatch(param: MethodHookParam) {
        if (!Prefs.settingsEntry) return

        val item = param.args.getOrNull(0) ?: return
        if (idOf(item) != ENTRY_ID) return

        openModuleSettings()
    }

    /**
     * 拉起模块自己的设置界面。
     *
     * 用 `Application` 而不是 Fragment 的 `getContext()`：后者要走
     * `BaseFragment` 的公开 API，各版本未必一致；而 `Application`
     * 是稳定可得的。代价是必须带 `FLAG_ACTIVITY_NEW_TASK`。
     *
     * `setPackage` 不可省 —— 它把解析范围锁死在模块自己包里，
     * 于是这个 intent 变成「指定包名」的，不受 Android 14 起
     * 「隐式 intent 只能拉起导出组件」那条限制的干扰
     * （何况设置页本来就声明了 `exported="true"`）。
     */
    private fun openModuleSettings() {
        try {
            val app = AndroidAppHelper.currentApplication()
            if (app == null) {
                XLog.w("[设置入口] 取不到 Application，无法打开设置界面")
                return
            }
            val intent = Intent(Prefs.ACTION_SETTINGS).apply {
                setPackage(Prefs.MODULE_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            XLog.result("设置入口", "已从 Telegram 设置页拉起模块设置界面")
        } catch (t: Throwable) {
            XLog.e("[设置入口] 打开模块设置界面失败（action 与清单是否一致？）", t)
        }
    }

    // ------------------------------------------------------------------

    /** 按名字与参数个数取一个静态工厂方法。 */
    private fun findFactory(cls: Class<*>, name: String, paramCount: Int): Method? = try {
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount }
    } catch (t: Throwable) {
        null
    }

    /**
     * 读 `UItem.viewType`。
     *
     * 字段定义在它的父类里，`XposedHelpers.getIntField` 会沿继承链查找，
     * 所以不用自己往上找。读不到时返回一个**不可能与真实常量相等**的值，
     * 让所有 `== 某常量` 的判断自然落空 —— 不要返回 0，
     * 那是 `VIEW_TYPE_HEADER` 的真实值，会造成误判。
     */
    private fun viewTypeOf(item: Any?): Int = try {
        XposedHelpers.getIntField(item, "viewType")
    } catch (t: Throwable) {
        Int.MIN_VALUE
    }

    /** 读 `UItem.id`；读不到时同样返回哨兵值，避免和真实 id 混淆。 */
    private fun idOf(item: Any?): Int = try {
        XposedHelpers.getIntField(item, "id")
    } catch (t: Throwable) {
        Int.MIN_VALUE
    }
}
