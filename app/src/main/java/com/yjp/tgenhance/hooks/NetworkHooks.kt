package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
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

    fun install(classLoader: ClassLoader) {
        if (!Prefs.netEnabled) {
            XLog.i("[网络] 开关关闭，跳过")
            return
        }
        XLog.section("网络增强")

        if (Prefs.blockProxyProbe) {
            hookBlockProxyProbe(classLoader)
        } else {
            XLog.i("[网络] 代理探测防护：未启用")
        }
    }

    private fun hookBlockProxyProbe(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_CONNECTIONS_MANAGER, classLoader)
        if (cls == null) {
            XLog.e("[网络] 未找到 $CLS_CONNECTIONS_MANAGER")
            return
        }

        safe("代理探测防护") {
            XposedBridge.hookAllMethods(cls, "checkProxy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HookStats.hit("net.proxyProbe")
                    param.result = 0L
                    XLog.result("网络", "已阻止一次代理探测（真实 IP 不外泄）")
                }
            })
            XLog.result("网络", "checkProxy() 已接管：不再发起绕开代理的连通性探测")
        }
    }
}
