package com.yjp.tgenhance.hooks

import android.graphics.Typeface
import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Locale

/**
 * 界面与主题定制。
 *
 * 目前两项能力：
 *
 * 1) 使用系统字体
 *    Telegram 所有内嵌字体都经由 `AndroidUtilities.getTypeface(String assetPath)`
 *    加载（源码 2393 行），内部走 `Typeface.Builder` / `Typeface.createFromAsset`。
 *    接管该方法即可整体替换，无需碰任何 UI 代码。
 *
 * 2) 隐藏 Stories
 *    聊天列表头部的 Stories 环由 `StoriesController` 的若干布尔查询方法驱动，
 *    统一返回 false 即可收起入口。
 */
object ThemeHooks {

    private const val CLS_ANDROID_UTILITIES = "org.telegram.messenger.AndroidUtilities"
    private const val CLS_STORIES_CONTROLLER = "org.telegram.ui.Stories.StoriesController"

    /** 只替换这些 Telegram 内嵌 Roboto 字族，避免误伤 emoji / 特殊字体。 */
    private val REPLACEABLE_FONT_KEYS = listOf(
        "rmedium", "rextrabold", "rmediumitalic", "rbold", "roboto", "rmono", "mw_bold"
    )

    /** 与 Stories 展示相关的布尔查询方法（跨版本做多候选尝试）。 */
    private val STORIES_BOOLEAN_METHODS = listOf(
        "hasStories",
        "hasUnreadStories",
        "hasHiddenStories",
        "hasSelfStories",
        "hasRecentStories",
    )

    fun install(classLoader: ClassLoader) {
        if (!Prefs.uiEnabled) {
            XLog.i("[界面] 开关关闭，跳过")
            return
        }
        XLog.section("界面与主题定制")

        if (Prefs.systemFont) {
            hookSystemTypeface(classLoader)
        } else {
            XLog.i("[界面] 系统字体：未启用")
        }

        if (Prefs.hideStories) {
            hookHideStories(classLoader)
        } else {
            XLog.i("[界面] 隐藏 Stories：未启用")
        }
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

        safe("系统字体") {
            XposedBridge.hookAllMethods(cls, "getTypeface", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val assetPath = param.args.getOrNull(0) as? String ?: return
                    HookStats.hit("ui.typeface.seen")
                    val mapped = mapToSystemTypeface(assetPath) ?: return
                    HookStats.hit("ui.typeface.replaced")
                    param.result = mapped
                }
            })
            XLog.result("界面", "已接管 AndroidUtilities.getTypeface() -> 系统字体")
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

        var hooked = 0
        for (methodName in STORIES_BOOLEAN_METHODS) {
            safe("Stories.$methodName") {
                XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val m = param.method as? Method ?: return
                        if (m.returnType == Boolean::class.javaPrimitiveType) {
                            HookStats.hit("ui.stories")
                            param.result = false
                        }
                    }
                })
                hooked++
            }
        }

        if (hooked > 0) {
            XLog.result("界面", "StoriesController 上已接管 $hooked 组查询方法，Stories 入口将被隐藏")
        } else {
            XLog.w("[Stories] 未匹配到任何可接管的方法，隐藏可能无效")
        }
    }
}
