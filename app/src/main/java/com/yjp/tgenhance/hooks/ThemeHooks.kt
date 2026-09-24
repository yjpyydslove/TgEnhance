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
    private const val CLS_SHARED_CONFIG = "org.telegram.messenger.SharedConfig"

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
            "[界面] 配置快照：系统字体=${Prefs.systemFont}、隐藏 Stories=${Prefs.hideStories}、" +
                "强制平板=${Prefs.forceTablet}、关闭更新提示=${Prefs.disableUpdateCheck}" +
                "（运行期实时读取，改设置无需重启）"
        )

        hookSystemTypeface(classLoader)
        hookHideStories(classLoader)
        hookForceTablet(classLoader)
        hookDisableUpdateCheck(classLoader)
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
        // 走候选路径：Stories 从 org.telegram.messenger 搬到 org.telegram.ui.Stories 过，
        // 各 fork 停在哪个位置不一定
        val cls = ClientProfileDetector.storiesControllerClass(classLoader)
        if (cls == null) {
            XLog.w("[Stories] 所有候选路径均未命中 StoriesController，该功能在本客户端不可用")
            HookStatus.markUnavailable(Prefs.HIDE_STORIES)
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
                        guard("隐藏 Stories") {
                            if (!Prefs.uiEnabled || !Prefs.hideStories) return@guard
                            HookStats.hit("ui.stories")
                            param.result = false
                        }
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

    // ------------------------------------------------------------------
    // 强制平板布局
    // ------------------------------------------------------------------

    /**
     * 强制平板布局（v5.8.0 新增）。
     *
     * hook `AndroidUtilities.isTabletForce()` 与 `isTabletInternal()`（源码 2948 / 2952）——
     * Telegram 判断要不要走平板版界面（左右分栏）全看这两个。
     *
     * 两个都要接管：`isTabletInternal()` 内部会把结果缓存进静态字段，
     * 只改前者的话首次调用之后就不再走原来那条路。
     */
    private fun hookForceTablet(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_ANDROID_UTILITIES, classLoader)
        if (cls == null) {
            XLog.e("[界面] 未找到 $CLS_ANDROID_UTILITIES")
            return
        }

        val targets = HookFinder.match(
            cls,
            explicitNames = listOf("isTabletForce", "isTabletInternal"),
            returnType = Boolean::class.javaPrimitiveType
        )
        if (targets.isEmpty()) {
            XLog.w("[界面] 未定位到平板判定方法，强制平板布局不可用")
            HookStatus.markUnavailable(Prefs.FORCE_TABLET)
            return
        }

        safe("强制平板布局") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("强制平板布局") {
                            if (!Prefs.uiEnabled || !Prefs.forceTablet) return@guard
                            HookStats.hit("ui.tablet")
                            param.result = true
                        }
                    }
                })
            }
            XLog.result("界面", "平板判定已接管 ${targets.size} 处：开启后强制平板布局")
        }
    }

    // ------------------------------------------------------------------
    // 关闭更新提示
    // ------------------------------------------------------------------

    /**
     * 关闭更新提示（v5.8.0 新增）。
     *
     * hook `SharedConfig.isAppUpdateAvailable()`（源码 777 行）—— 返回 false 即可，
     * 不碰任何更新逻辑本身，只是不再弹提示。
     */
    private fun hookDisableUpdateCheck(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_SHARED_CONFIG, classLoader)
        if (cls == null) {
            XLog.e("[界面] 未找到 $CLS_SHARED_CONFIG")
            return
        }

        val targets = HookFinder.match(
            cls,
            explicitNames = listOf("isAppUpdateAvailable"),
            returnType = Boolean::class.javaPrimitiveType
        )
        if (targets.isEmpty()) {
            XLog.w("[界面] 未定位到 isAppUpdateAvailable，关闭更新提示不可用")
            HookStatus.markUnavailable(Prefs.DISABLE_UPDATE_CHECK)
            return
        }

        safe("关闭更新提示") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guard("关闭更新提示") {
                            if (!Prefs.uiEnabled || !Prefs.disableUpdateCheck) return@guard
                            HookStats.hit("ui.updateCheck")
                            param.result = false
                        }
                    }
                })
            }
            XLog.result("界面", "isAppUpdateAvailable() 已接管：开启后不再提示更新")
        }
    }
}
