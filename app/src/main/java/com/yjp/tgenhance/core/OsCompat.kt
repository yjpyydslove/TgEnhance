package com.yjp.tgenhance.core

import android.os.Build

/**
 * Android 版本差异的唯一落点（v N1.1）。
 *
 * ### 为什么要有这个文件
 *
 * 本模块只发**一个 APK**，却要同时跑在 Android 14 到 17 上。
 * 这四个版本之间有几处会直接影响模块的改动：
 *
 *  - **动态注册广播接收器**：Android 14（API 34）起，
 *    `registerReceiver` 必须显式声明 `RECEIVER_EXPORTED` 或
 *    `RECEIVER_NOT_EXPORTED`，否则直接抛 `SecurityException`。
 *    但 API 33 及以下**不认识**这两个常量对应的重载 —— 传了会崩。
 *  - **隐式 Intent 启动**：Android 14 起，隐式 intent 只能启动
 *    `exported=true` 的组件；模块自己内部跳转必须用显式 intent。
 *  - **前台服务类型**：Android 14 起 `startForeground` 必须声明类型；
 *    Android 15 进一步收紧。本模块不使用前台服务，这里只做判定备用。
 *  - **edge-to-edge**：Android 15（API 35）起对 `targetSdk 35` 的应用**强制**
 *    全屏，`statusBarColor` 之类不再生效，必须自己处理 insets。
 *
 * 把这些分支散落在各处，很容易出现「在 14 上正常、在 13 上崩」这种
 * 只在老机器复现的问题 —— 而本机没有对应设备，根本测不出来。
 * 集中在这里，至少能保证每个分支只有一个地方需要读、需要改。
 *
 * ### 注意
 *
 * 本文件**不引用任何 Xposed API**，也不在类初始化时访问系统服务，
 * 因此设置界面进程与 hook 进程都能安全加载。
 * 所有常量都比较的是 [Build.VERSION.SDK_INT]，不做字符串比较 ——
 * 有些定制 ROM 会把 `RELEASE` 改成奇怪的值。
 */
object OsCompat {

    /** Android 14 —— 动态注册接收器必须声明导出标志。 */
    const val API_34 = 34

    /** Android 15 —— 强制 edge-to-edge、前台服务类型收紧。 */
    const val API_35 = 35

    /** Android 16 —— 目前未发现影响本模块的行为变更。 */
    const val API_36 = 36

    /** Android 17 —— 目前未发现影响本模块的行为变更。 */
    const val API_37 = 37

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    /**
     * 动态注册广播接收器时，是否需要显式传导出标志。
     *
     * API 34 起是**必须**的：不传会抛
     * `SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
     * should be specified for receivers that are not exclusively registered
     * to system broadcasts`。
     */
    val needsReceiverExportFlag: Boolean get() = sdkInt >= API_34

    /**
     * 系统是否强制 edge-to-edge（内容画到状态栏 / 导航栏底下）。
     *
     * API 35 起，`targetSdk >= 35` 的应用被强制全屏；
     * 本模块 targetSdk 就是 35，所以从 35 开始都要自己留安全区。
     */
    val forcesEdgeToEdge: Boolean get() = sdkInt >= API_35

    /**
     * 隐式 Intent 是否被限制只能启动 exported 组件。
     *
     * API 34 起，`startActivity` 传隐式 intent 时，
     * 目标组件必须显式 `exported="true"`，否则 `ActivityNotFoundException`。
     * 模块内部跳转一律用显式 intent（指定包名+类名），不受影响；
     * 这个判定留给「需要唤起系统组件」的场景。
     */
    val restrictsImplicitIntents: Boolean get() = sdkInt >= API_34

    /**
     * 供日志与诊断报告展示的一行版本描述。
     *
     * 把「SDK 等级 + 我们关心的几个分支的判定结果」一起写出来，
     * 用户反馈时一眼就能看出是哪个分支生效了 ——
     * 比让他去翻系统设置里念 Android 版本号靠谱。
     */
    fun summary(): String = buildString {
        append("Android ").append(Build.VERSION.RELEASE)
        append("（API ").append(sdkInt).append(')')
        append("  接收器导出标志=").append(if (needsReceiverExportFlag) "必需" else "不传")
        append("  强制全屏=").append(if (forcesEdgeToEdge) "是" else "否")
        append("  隐式意图限制=").append(if (restrictsImplicitIntents) "是" else "否")
        // 比已知上限还新的系统上，前面几个分支的判定未必还成立。
        // 写进报告里，日后有人拿新系统反馈时能一眼对上（v N1.12 补上）
        if (isNewerThanKnown()) {
            append("  ⚠ 系统高于本模块已知范围，按最新已知行为处理")
        }
    }

    /**
     * 本模块是否声明支持当前系统。
     *
     * 只做**上界**提示：比 [API_37] 还新的系统上，前面那些分支的判定
     * 未必还对，但通常不会更宽松 —— 所以按最新已知行为处理，
     * 只在日志里提一句，便于日后有人拿新系统反馈时能立刻对上。
     */
    fun isNewerThanKnown(): Boolean = sdkInt > API_37
}
