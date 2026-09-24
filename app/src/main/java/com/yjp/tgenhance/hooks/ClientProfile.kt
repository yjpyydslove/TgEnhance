package com.yjp.tgenhance.hooks

import android.app.AndroidAppHelper
import de.robv.android.xposed.XposedHelpers

/**
 * 已知的 Telegram 系客户端。
 *
 * 第三方 fork 大多保留 `org.telegram.*` 的包结构，但**主 Activity、Stories 等
 * 后加入的模块会被搬到自己的命名空间** —— 这是「同一份 Hook 代码在官方版能用、
 * 在 Nekogram 上就静默失效」最常见的原因。
 */
enum class ClientKind(val displayName: String) {
    OFFICIAL("Telegram 官方"),
    NEKOGRAM("Nekogram"),
    NEKOX("NekoX"),
    PLUS("Telegram Plus"),
    EXTERA("ExteraGram"),
    FORKGRAM("Forkgram"),
    TURRIT("Turrit"),
    UNKNOWN("未知客户端")
}

/** 一次客户端识别的结果。 */
data class ClientProfile(
    val packageName: String,
    val versionName: String,
    val kind: ClientKind,
    /** 实际存在的主 Activity 类名；一个都没找到时为 null。 */
    val launchActivity: String?,
    /** 实际存在的 StoriesController 类名；一个都没找到时为 null。 */
    val storiesController: String?,
    /**
     * 实际存在的设置页 Fragment 类名；一个都没找到时为 null（v N1.1）。
     *
     * 仅用于探测 —— 目前**不**往 TG 设置页里插条目，只用来判断
     * 「这个客户端适不适合做注入」。列表构建方式在各版本 / fork 之间
     * 差异太大（旧版用 `rowInfo`，新版用 `UItem` + `UniversalAdapter`），
     * 贸然注入会在部分客户端上把设置页画错，得不偿失。
     */
    val settingsFragment: String?
) {
    /** 供日志与界面展示的一行摘要。 */
    fun summary(): String = buildString {
        append(kind.displayName).append(' ').append(versionName)
        append("（").append(packageName).append("）")
    }

    /**
     * 版本号是否低于本模块的适配下限。
     *
     * 本模块的 Hook 点取自 Telegram 10.x 时期的源码。更早的版本上，
     * 一部分类与方法（Stories、已读回执的新实现等）根本不存在 ——
     * 模块会跳过它们并继续工作，但用户看到的是「某个开关点了没反应」。
     * 与其让他逐项排查，不如直接说明「你的客户端偏旧」。
     */
    fun isBelowSupportedVersion(): Boolean {
        val current = versionNumber()
        return current > 0 && current < MIN_SUPPORTED_VERSION
    }

    /** 把 `11.2.3` 这样的版本名压成可比较的整数（110203）。解析不出时返回 0。 */
    private fun versionNumber(): Int {
        val parts = versionName.split('.')
        if (parts.isEmpty()) return 0
        var result = 0
        for (i in 0 until 3) {
            val piece = parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            result = result * 100 + piece.coerceIn(0, 99)
        }
        return result
    }

    companion object {
        /** 适配下限：低于此版本的客户端只保证部分功能可用。 */
        private const val MIN_SUPPORTED_VERSION = 9_00_00
    }
}

/**
 * 客户端识别与类路径解析。
 *
 * ### 为什么需要「候选路径」而不是写死一个类名
 *
 * Telegram 自己就搬过类：Stories 最早在 `org.telegram.messenger`，
 * 后来整体挪到 `org.telegram.ui.Stories`。fork 更自由，可能留在旧位置，
 * 也可能再挪一次。写死路径的话，换一个客户端就整组功能失效，
 * 而表现只是「开关点了没反应」，用户根本无从判断。
 *
 * 所以这里维护**候选列表**，逐个探测取第一个存在的。探测本身很便宜
 * （一次类加载尝试），换来的是一份代码能在多个客户端上跑。
 */
object ClientProfileDetector {

    /** 包名到客户端类型的映射，按特异性从高到低排列（先匹配更具体的）。 */
    private val PACKAGE_RULES = listOf(
        "tw.nekogram" to ClientKind.NEKOGRAM,
        "nekox.messenger" to ClientKind.NEKOX,
        "org.telegram.plus" to ClientKind.PLUS,
        "com.exteragram" to ClientKind.EXTERA,
        "org.forkclient" to ClientKind.FORKGRAM,
        "org.telegram.messenger.web" to ClientKind.TURRIT,
        "org.telegram.messenger" to ClientKind.OFFICIAL,
    )

    /** 主 Activity 候选。LaunchActivity 是配置热更新与诊断回传的挂载点。 */
    private val LAUNCH_ACTIVITY_CANDIDATES = listOf(
        "org.telegram.ui.LaunchActivity",
        "tw.nekogram.NekoLaunchActivity",
        "org.telegram.ui.LaunchActivity2",
    )

    /** StoriesController 候选，顺序即优先级。 */
    private val STORIES_CANDIDATES = listOf(
        "org.telegram.ui.Stories.StoriesController",
        "org.telegram.messenger.StoriesController",
    )

    /**
     * 设置页 Fragment 候选（v N1.1，仅供探测）。
     *
     * 官方版一直是 `org.telegram.ui.SettingsActivity`（名字叫 Activity，
     * 实际继承 `BaseFragment`，是 Telegram 早期命名留下的历史包袱）。
     * fork 里可能被挪走，所以留几个候选。
     */
    private val SETTINGS_CANDIDATES = listOf(
        "org.telegram.ui.SettingsActivity",
        "org.telegram.ui.Settings.SettingsActivity",
        "tw.nekogram.settings.NekoSettingsActivity",
    )

    @Volatile
    private var cached: ClientProfile? = null

    fun detect(classLoader: ClassLoader, packageName: String): ClientProfile {
        cached?.let { if (it.packageName == packageName) return it }

        val profile = ClientProfile(
            packageName = packageName,
            versionName = resolveVersionName(packageName),
            kind = resolveKind(packageName),
            launchActivity = resolveLaunchActivity(classLoader, packageName),
            storiesController = resolveFirst(classLoader, STORIES_CANDIDATES),
            settingsFragment = resolveFirst(classLoader, SETTINGS_CANDIDATES)
        )
        cached = profile
        return profile
    }

    /** 解析出实际存在的设置页类；找不到时返回 null。 */
    fun settingsFragmentClass(classLoader: ClassLoader): Class<*>? =
        resolveFirst(classLoader, SETTINGS_CANDIDATES)?.let { findClass(it, classLoader) }

    /** 最近一次识别结果。 */
    fun current(): ClientProfile? = cached

    /** 解析出实际存在的主 Activity 类；找不到时返回 null。 */
    fun launchActivityClass(classLoader: ClassLoader): Class<*>? {
        val name = cached?.launchActivity
            ?: resolveLaunchActivity(classLoader, cached?.packageName.orEmpty())
        return name?.let { findClass(it, classLoader) }
    }

    /** 解析出实际存在的 StoriesController 类；找不到时返回 null。 */
    fun storiesControllerClass(classLoader: ClassLoader): Class<*>? {
        val name = cached?.storiesController ?: resolveFirst(classLoader, STORIES_CANDIDATES)
        return name?.let { findClass(it, classLoader) }
    }

    // ------------------------------------------------------------------

    private fun resolveKind(packageName: String): ClientKind {
        val lower = packageName.lowercase()
        return PACKAGE_RULES.firstOrNull { lower.startsWith(it.first) }?.second ?: ClientKind.UNKNOWN
    }

    /**
     * 解析主 Activity：先试已知候选，再问系统「这个包启动时进的是哪个 Activity」。
     *
     * 加这个兜底是因为 fork 实在太多 —— 与其往候选列表里不断堆猜出来的类名，
     * 不如直接从系统的启动信息里读。拿到的名字一定真实存在，
     * 最多是「拿到的不是主界面而是别的入口」这种无害情况
     * （那种情况下配置热更新不生效，但不会报错）。
     */
    private fun resolveLaunchActivity(classLoader: ClassLoader, packageName: String): String? {
        resolveFirst(classLoader, LAUNCH_ACTIVITY_CANDIDATES)?.let { return it }
        if (packageName.isEmpty()) return null

        return try {
            val app = AndroidAppHelper.currentApplication() ?: return null
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            app.packageManager.queryIntentActivities(intent, 0)
                ?.firstOrNull()
                ?.activityInfo
                ?.name
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveFirst(classLoader: ClassLoader, candidates: List<String>): String? {
        for (name in candidates) {
            if (findClass(name, classLoader) != null) return name
        }
        return null
    }

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? = try {
        XposedHelpers.findClassIfExists(name, classLoader)
    } catch (t: Throwable) {
        null
    }

    private fun resolveVersionName(packageName: String): String = try {
        AndroidAppHelper.currentApplication()
            ?.packageManager
            ?.getPackageInfo(packageName, 0)
            ?.versionName
            ?: "未知"
    } catch (t: Throwable) {
        "未知"
    }
}
