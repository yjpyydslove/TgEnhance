package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.guard
import com.yjp.tgenhance.XLog.safe
import com.yjp.tgenhance.diag.HookStats
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import java.util.ArrayList

/**
 * 广告屏蔽（v5.9.0）。
 *
 * ### 关于这一项
 *
 * 赞助消息是 Telegram 的收入来源。本模块**只**做这一件事，且默认关闭、
 * 开启前弹窗说明影响 —— 用户明确知道自己放弃了什么再打开，
 * 而不是装完就被默认扣掉。
 *
 * 本模块不做的事：伪造 Premium、去视频贴片广告、绕过内容保存与转发限制。
 * 换句话说，这里处理的是「不想在聊天界面里看到推广内容」这一个小需求，
 * 不是「把 Telegram 的商业模式拆掉」。
 *
 * ### Hook 点
 *
 * `MessagesController.getSponsoredMessages(long dialogId)`（源码 21612 行）——
 * 它是赞助消息的**唯一入口**：先查缓存，没有就发
 * `TL_messages_getSponsoredMessages` 请求，再把响应里的
 * `TL_sponsoredMessage` 转成 MessageObject 塞进 `SponsoredMessagesInfo.messages`，
 * 界面拿这个列表渲染推广内容。
 *
 * 在它**执行前**直接返回一个空壳 info，效果是：
 *   - 请求根本不发出去（顺带省流量）
 *   - 界面拿到的列表永远是空的
 *
 * 比「拦网络响应」或「隐藏 UI 元素」都干净：不碰数据包，也不碰视图层。
 */
object AdHooks {

    private const val CLS_MESSAGES_CONTROLLER = "org.telegram.messenger.MessagesController"
    private const val CLS_SPONSORED_INFO =
        "org.telegram.messenger.MessagesController\$SponsoredMessagesInfo"

    /** 字段名带下划线，是 Telegram 自己的命名（`posts_between`）。 */
    private const val FIELD_MESSAGES = "messages"
    private const val FIELD_POSTS_BETWEEN = "posts_between"
    private const val FIELD_LOADING = "loading"
    private const val FIELD_LOAD_TIME = "loadTime"

    fun install(classLoader: ClassLoader) {
        XLog.section("广告屏蔽")
        XLog.i(
            "[广告] 配置快照：开关=${Prefs.adsEnabled}、屏蔽赞助消息=${Prefs.blockSponsored}" +
                "（运行期实时读取，改设置无需重启）"
        )
        hookSponsoredMessages(classLoader)
    }

    private fun hookSponsoredMessages(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_MESSAGES_CONTROLLER, classLoader)
        if (cls == null) {
            XLog.e("[广告] 未找到 $CLS_MESSAGES_CONTROLLER")
            return
        }

        val targets = HookFinder.matchByKey(cls, "ads.sponsored.blocked")
        if (targets.isEmpty()) {
            XLog.w("[广告] 未定位到 getSponsoredMessages，广告屏蔽不可用")
            HookStatus.markUnavailable(Prefs.BLOCK_SPONSORED)
            return
        }

        safe("屏蔽赞助消息") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guard("屏蔽赞助消息") {
                            if (!Prefs.adsEnabled || !Prefs.blockSponsored) return@guard

                            val empty = emptySponsoredInfo(classLoader, param.thisObject)
                            if (empty == null) return@guard

                            // 短路原方法：请求不会发出，界面拿到的是空列表
                            param.result = empty
                            HookStats.hit("ads.sponsored.blocked")
                        }
                    }
                })
            }
            XLog.result("广告", "getSponsoredMessages() 已接管：开启后不再请求赞助内容")
        }
    }

    /**
     * 构造一个「没有赞助消息」的 info 对象。
     *
     * `SponsoredMessagesInfo` 是 MessagesController 的**非静态内部类**，
     * 构造它必须传入外部类实例，所以不能简单 newInstance。
     *
     * 构造失败时返回 null，调用方不设 result —— 此时会退回原逻辑（广告照常显示），
     * 属于可接受的降级：宁可没屏蔽，也不要让界面拿到一个半成品对象。
     */
    private fun emptySponsoredInfo(classLoader: ClassLoader, controller: Any): Any? = try {
        val infoCls = XposedHelpers.findClass(CLS_SPONSORED_INFO, classLoader)
        val controllerCls = XposedHelpers.findClass(CLS_MESSAGES_CONTROLLER, classLoader)

        val ctor = infoCls.getDeclaredConstructor(controllerCls).apply { isAccessible = true }
        val info = ctor.newInstance(controller)

        XposedHelpers.setObjectField(info, FIELD_MESSAGES, ArrayList<Any>())
        // posts_between 是「每多少条消息插一条推广」，设成极大值等于永不插入
        XposedHelpers.setObjectField(info, FIELD_POSTS_BETWEEN, Integer.valueOf(Int.MAX_VALUE))
        XposedHelpers.setBooleanField(info, FIELD_LOADING, false)
        XposedHelpers.setLongField(info, FIELD_LOAD_TIME, System.currentTimeMillis())
        info
    } catch (t: Throwable) {
        XLog.e("[广告] 构造空的赞助消息对象失败，本次放行", t)
        null
    }
}
