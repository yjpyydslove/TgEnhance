package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 网络增强。
 *
 * 当前核心能力：阻止「代理连通性自动探测」。
 *
 * 背景（真实攻击面）：Telegram 在添加一个代理之前，会先向该代理地址发一次
 * 连通性探测请求；这次探测**不经过任何已配置的代理/VPN**，直接走设备原生网络栈。
 * 攻击者只要把恶意代理链接伪装成用户名让你点一下，就能拿到你的真实出口 IP。
 *
 * 对应的 Java 侧入口是 `ConnectionsManager.checkProxy(ProxySettings, RequestTimeDelegate)`
 * （源码 739 行，内部调用 native_checkProxy）。接管它并直接返回 0，
 * 即可跳过探测、避免真实 IP 外泄。
 *
 * 代价：设置界面里的「检查代理」也就不会再给出延迟测速结果。
 */
object NetworkHooks {

    private const val CLS_CONNECTIONS_MANAGER = "org.telegram.tgnet.ConnectionsManager"
    private const val CLS_DOWNLOAD_CONTROLLER = "org.telegram.messenger.DownloadController"
    private const val CLS_SHARED_CONFIG = "org.telegram.messenger.SharedConfig"

    fun install(classLoader: ClassLoader) {
        XLog.section("网络增强")
        XLog.i(
            "[网络] 配置快照：阻止代理探测=${Prefs.blockProxyProbe}、" +
                "阻止自动下载=${Prefs.blockAutoDownload}、" +
                "禁用自动播放=${Prefs.disableAutoplay}（运行期实时读取，改设置无需重启）"
        )
        hookBlockProxyProbe(classLoader)
        hookBlockAutoDownload(classLoader)
        hookDisableAutoplay(classLoader)
    }

    private fun hookBlockProxyProbe(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_CONNECTIONS_MANAGER, classLoader)
        if (cls == null) {
            XLog.e("[网络] 未找到 $CLS_CONNECTIONS_MANAGER")
            return
        }

        val targets = HookFinder.matchByKey(cls, "net.proxyProbe")
        if (targets.isEmpty()) {
            XLog.w("[网络] 未定位到 checkProxy，代理探测防护不可用")
            HookStatus.markUnavailable(Prefs.BLOCK_PROXY_PROBE)
            return
        }

        safe("代理探测防护") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 包 guard 的原因见 AccountHooks.getMaxAccountCount：
                        // `param.result` 的赋值在返回类型对不上时会抛，
                        // 要让它落进「回调异常」计数，而不是变成归因不明的框架日志
                        guard("代理探测防护") {
                            HookStats.hit("net.proxyProbe")
                            if (!Prefs.netEnabled || !Prefs.blockProxyProbe) return@guard
                            param.result = 0L
                            XLog.result("网络", "已阻止一次代理探测（真实 IP 不外泄）")
                        }
                    }
                })
            }
            XLog.result(
                "网络",
                "checkProxy() 已接管 ${targets.size} 个重载：开启后不再发起绕开代理的连通性探测"
            )
        }
    }

    /**
     * 禁用自动播放（v3.4.0 新增）。
     *
     * hook 点：`SharedConfig.isAutoplayVideo()` / `isAutoplayGifs()`（源码 740 / 744）——
     * 两个无参静态布尔方法，聊天里的 GIF 与视频要不要自动播放全看它们。
     * 恒定返回 false 即可，不必碰任何播放器逻辑。
     */
    private fun hookDisableAutoplay(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_SHARED_CONFIG, classLoader)
        if (cls == null) {
            XLog.e("[网络] 未找到 $CLS_SHARED_CONFIG")
            HookStatus.markUnavailable(Prefs.DISABLE_AUTOPLAY)
            return
        }

        val targets = HookFinder.findMethods(
            cls,
            returnType = Boolean::class.javaPrimitiveType,
            namePrefix = "isAutoplay",
            paramCount = 0
        )
        if (targets.isEmpty()) {
            XLog.w("[网络] 未定位到 isAutoplay*()，禁用自动播放不可用")
            HookStatus.markUnavailable(Prefs.DISABLE_AUTOPLAY)
            return
        }

        safe("禁用自动播放") {
            for (method in targets) {
                XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        if (!Prefs.netEnabled || !Prefs.disableAutoplay) {
                            return invokeOriginal(param) ?: false
                        }
                        return try {
                            HookStats.hit("net.autoplay.blocked")
                            false
                        } catch (t: Throwable) {
                            XLog.e("[禁用自动播放] 回调异常，本次放行", t)
                            invokeOriginal(param) ?: false
                        }
                    }
                })
            }
            XLog.result(
                "网络",
                "SharedConfig 已接管 ${targets.size} 个 isAutoplay*()：开启后 GIF / 视频不自动播放"
            )
        }
    }

    /**
     * 阻止媒体自动下载（v2.4.0 新增）。
     *
     * hook 点：`DownloadController.canDownloadMedia(MessageObject)`（源码 609 行）——
     * 收到新消息时 Telegram 用它判断「这条媒体要不要自动下下来」。
     * 恒定返回 false，就只剩用户手动点击才会下载，达到省流量的目的。
     *
     * **刻意放行 `sponsoredMedia`**：本功能只为省流量，不碰任何广告 / 赞助内容的处理逻辑，
     * 与「去除赞助消息」是两件事。
     */
    private fun hookBlockAutoDownload(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_DOWNLOAD_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[网络] 未找到 $CLS_DOWNLOAD_CONTROLLER")
            return
        }

        // 只取「单个 MessageObject 参数」的那个重载，另一个 canDownloadMedia(int, long)
        // 是自动下载预设判断，不在本功能范围内
        val targets = HookFinder.findMethods(
            cls,
            returnType = Boolean::class.javaPrimitiveType,
            namePrefix = "canDownloadMedia",
            paramCount = 1
        ).filter { it.parameterTypes[0].name.endsWith("MessageObject") }

        if (targets.isEmpty()) {
            XLog.w("[网络] 未定位到 canDownloadMedia(MessageObject)，阻止自动下载不可用")
            HookStatus.markUnavailable(Prefs.BLOCK_AUTO_DOWNLOAD)
            return
        }

        safe("阻止媒体自动下载") {
            for (method in targets) {
                XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        if (!Prefs.netEnabled || !Prefs.blockAutoDownload) {
                            return invokeOriginal(param) ?: false
                        }
                        return try {
                            if (isSponsored(param)) invokeOriginal(param) ?: false
                            else {
                                HookStats.hit("net.autoDownload.blocked")
                                false
                            }
                        } catch (t: Throwable) {
                            XLog.e("[阻止自动下载] 回调异常，本次放行", t)
                            invokeOriginal(param) ?: false
                        }
                    }
                })
            }
            XLog.result("网络", "canDownloadMedia() 已接管：开启后媒体只手动下载")
        }
    }

    /** 判断这条消息是否为赞助内容；是则保持原行为，不介入。 */
    private fun isSponsored(param: MethodHookParam): Boolean = try {
        val message = param.args.getOrNull(0) ?: return false
        XposedHelpers.getObjectField(message, "sponsoredMedia") != null
    } catch (t: Throwable) {
        false
    }

    private fun invokeOriginal(param: MethodHookParam): Any? =
        try {
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
        } catch (t: Throwable) {
            XLog.e("回退原方法失败 (${param.method.name}): ${t.message}")
            null
        }
}
