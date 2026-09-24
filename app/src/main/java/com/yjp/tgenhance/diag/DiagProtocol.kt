package com.yjp.tgenhance.diag

/**
 * 诊断回传的广播协议常量。
 *
 * 单独抽出来是因为 **两端都会引用它**：hook 端（Telegram 进程）发广播，
 * 设置界面（模块自身进程）收广播。而 hook 端代码依赖 Xposed API，
 * 模块进程里没有这些类，一旦误加载就会 `NoClassDefFoundError`。
 * 本文件只引用 `java.lang`，两个进程都能安全加载。
 */
object DiagProtocol {

    /** 显式广播（`setPackage` 指向模块包名），不受 Android 隐式广播限制。 */
    const val ACTION = "com.yjp.tgenhance.DIAG_REPORT"

    /** 回传令牌：由模块侧生成，hook 侧从配置里读出后带上，用于过滤伪造广播。 */
    const val EXTRA_TOKEN = "token"

    /** 载荷：`key=value` 逐行的简单文本，避免两端 JSON 版本差异。 */
    const val EXTRA_PAYLOAD = "payload"

    /** 快照里时间行的 key。 */
    const val KEY_TIME = "time"

    /**
     * 自检清单段的分隔行。
     *
     * 整份载荷是一段朴素文本，用这一行把它切成两半：
     * 前半是 hook 触发计数，后半是挂载期自检结果。
     */
    const val SECTION_SELFCHECK = "--selfcheck--"

    /** 自检命中标记。 */
    const val MARK_OK = "OK "

    /** 自检未命中标记。 */
    const val MARK_MISS = "MISS "
}
