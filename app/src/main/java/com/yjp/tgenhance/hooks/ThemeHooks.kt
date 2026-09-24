package com.yjp.tgenhance.hooks

import android.graphics.Typeface
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Locale

/**
 * 界面与主题定制。
 *
 * 两项能力：
 *
 * 1) 使用系统字体
 *    Telegram 所有内嵌字体都经由 `AndroidUtilities.getTypeface(String assetPath)`
 *    加载（源码 2393 行），内部走 `Typeface.Builder` / `Typeface.createFromAsset`。
 *    接管该方法即可整体替换，无需碰任何 UI 代码。
 *
 * 2) 隐藏 Stories
 *    聊天列表头部的 Stories 环由 `org.telegram.ui.Stories.StoriesController` 的
 *    若干布尔查询方法驱动，统一返回 false 即可收起入口。
 *
 *    v2.0.0 起改用 [HookFinder] 按特征定位，不再依赖写死的方法名 ——
 *    已知的 4 个查询方法作为「精确名单」保证不误伤，特征匹配作为兜底保证
 *    官方改名后仍能命中。
 *
 *    刻意**不**接管 `hasLiveStory` / `hasUploadingStories` / `hasLoadingStories`：
 *    它们表达的是「加载中」，返回 false 会打乱状态而不是隐藏入口。
 */
object ThemeHooks {

    private const val CLS_ANDROID_UTILITIES = "org.telegram.messenger.AndroidUtilities"
    private const val CLS_STORIES_CONTROLLER = "org.telegram.ui.Stories.StoriesController"

    /** 只替换这些 Telegram 内嵌 Roboto 字族，避免误伤 emoji / 特殊字体。 */
    private val REPLACEABLE_FONT_KEYS = listOf(
        "rmedium", "rextrabold", "rmediumitalic", "rbold", "roboto", "rmono", "mw_bold"
    )

    /**
     * Stories 查询方法精确名单（已对照官方源码逐条核对语义）。
     *
     * 兜底特征为「返回 boolean + 以 has 开头 + 名字含 stor」。
     */
    private val STORIES_QUERY_NAMES = listOf(
        "hasStories",
        "hasUnreadStories",
        "hasHiddenStories",
        "hasSelfStories",
    )

    fun install(classLoader: ClassLoader) {
        XLog.section("界面与主题定制")
        XLog.i(
            "[界面] 配置快照：系统字体=${Prefs.systemFont}、" +
                "隐藏 Stories=${Prefs.hideStories}（运行期实时读取，改设置无需重启）"
        )

        hookSystemTypeface(classLoader)
        hookHideStories(classLoader)
    }

    // ------------------------------------------------------------------
    // 系统字体
    // ------------------------------------------------------------------

    private fun hookSystemTypeface(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_ANDROID_UTILITIES, classLoader)
        if (cls == null) {
            XLog.e("[字体] 未找到 $CLS_ANDROID_UTILITIES")
            return
        }

        val targets = HookFinder.findMethods(
            cls,
            returnType = Typeface::class.java,
            namePrefix = "getTypeface"
        )
        if (targets.isEmpty()) {
            XLog.e("[字体] AndroidUtilities 上未定位到返回 Typeface 的 getTypeface 重载")
            return
        }

        safe("系统字体") {
            for (method in targets) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("字体替换") {
                            val assetPath = param.args.getOrNull(0) as? String ?: return@guard
                            HookStats.hit("ui.typeface.seen")
                            if (!Prefs.uiEnabled || !Prefs.systemFont) return@guard
                            val mapped = mapToSystemTypeface(assetPath) ?: return@guard
                            HookStats.hit("ui.typeface.replaced")
                            param.result = mapped
                        }
                    }
                })
            }
            XLog.result(
                "界面",
                "已接管 ${targets.size} 个 getTypeface 重载 -> 系统字体（开启后生效）"
            )
        }
    }

    /**
     * 把 Telegram 内嵌字体映射到系统字体。
     * 返回 null 表示「不接管，保持原样」。
     */
    private fun mapToSystemTypeface(assetPath: String): Typeface? {
        val lower = assetPath.lowercase(Locale.ROOT)
        if (REPLACEABLE_FONT_KEYS.none { lower.contains(it) }) return null

        return when {
            // 代码块等需要等宽，保留 monospace 语义
            lower.contains("mono") -> Typeface.MONOSPACE
            lower.contains("extrabold") || lower.contains("bold") || lower.contains("medium") ->
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            lower.contains("italic") -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    // ------------------------------------------------------------------
    // 隐藏 Stories
    // ------------------------------------------------------------------

    private fun hookHideStories(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_STORIES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.w("[Stories] 未找到 $CLS_STORIES_CONTROLLER（可能是版本差异），已跳过")
            return
        }

        val targets = HookFinder.match(
            cls,
            explicitNames = STORIES_QUERY_NAMES,
            returnType = Boolean::class.javaPrimitiveType,
            namePrefix = "has",
            nameContains = "stor"
        )
        if (targets.isEmpty()) {
            XLog.w("[Stories] 未匹配到任何查询方法，隐藏可能无效")
            return
        }

        safe("隐藏 Stories") {
            for (method in targets) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Prefs.uiEnabled || !Prefs.hideStories) return
                        HookStats.hit("ui.stories")
                        param.result = false
                    }
                })
            }
            XLog.result(
                "界面",
                "StoriesController 已接管 ${targets.size} 个查询方法：" +
                    targets.joinToString(", ") { it.name }
            )
        }
    }
}
