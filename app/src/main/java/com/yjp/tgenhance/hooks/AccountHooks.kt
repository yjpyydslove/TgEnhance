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
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 多账号上限提升。
 *
 * Telegram 的账号体系由一组「单例数组」构成，每个类都是同一套模板：
 *
 *     private static volatile Xxx[] Instance = new Xxx[MAX_ACCOUNT_COUNT];
 *     public static Xxx getInstance(int num) { ... Instance[num] ... }
 *
 * 而 `UserConfig.MAX_ACCOUNT_COUNT = 4` 是 `public final static int`，
 * **编译期常量会被内联到每一处引用**，因此单纯用反射改这个字段的值毫无作用
 * （方法体里早就写死成 4 了）。
 *
 * 真正的上限由两处共同决定：
 *   1. UI 层：`UserConfig.getMaxAccountCount()` —— 决定界面上允许添加几个账号；
 *   2. 内存层：上述 `Instance[]` 数组的容量 —— 决定能真正容纳几个账号实例。
 *
 * 所以本实现三管齐下：
 *   - hook `getMaxAccountCount()` 直接返回用户设定值；
 *   - 对每个管理器类的 `getInstance(int)` 挂前置钩子，索引越界时**按需扩容数组**；
 *   - 重写依赖 `MAX_ACCOUNT_COUNT` 常量循环的统计方法，避免超出 4 的账号不被计数。
 *
 * 扩容采用「按需」而非「启动即扩容」：只有真的用到第 N 个账号时才动数组，
 * 既避免启动期强制初始化整条 Manager 链（很重），也把对原逻辑的扰动降到最低。
 *
 * v2.0.0 起改为「钩子常驻 + 回调内读配置」，因此上限数值可以在运行期调整
 * （`Prefs.reload()` 之后立即按新值生效，无需重启）。
 */
object AccountHooks {

    private const val CLS_USER_CONFIG = "org.telegram.messenger.UserConfig"
    private const val CLS_ACCOUNT_INSTANCE = "org.telegram.messenger.AccountInstance"

    /**
     * 所有持有 `Instance[]` 单例数组的类。
     * 清单来源：`AccountInstance` 的 getter（它把所有账号级管理器都列全了）
     * 加上 Telegram 源码中同样模式的若干补充类。
     */
    private val CAPACITY_CLASSES = listOf(
        "org.telegram.messenger.AccountInstance",
        "org.telegram.messenger.UserConfig",
        "org.telegram.messenger.MessagesController",
        "org.telegram.messenger.MessagesStorage",
        "org.telegram.messenger.ContactsController",
        "org.telegram.messenger.MediaDataController",
        "org.telegram.messenger.MediaController",
        "org.telegram.messenger.NotificationCenter",
        "org.telegram.messenger.NotificationsController",
        "org.telegram.messenger.LocationController",
        "org.telegram.messenger.DownloadController",
        "org.telegram.messenger.SendMessagesHelper",
        "org.telegram.messenger.SecretChatHelper",
        "org.telegram.messenger.StatsController",
        "org.telegram.messenger.FileLoader",
        "org.telegram.messenger.FileRefController",
        "org.telegram.messenger.MemberRequestsController",
        "org.telegram.messenger.GiftAuctionController",
        "org.telegram.messenger.Emoji",
        "org.telegram.messenger.ImageLoader",
        "org.telegram.tgnet.ConnectionsManager",
    )

    fun install(classLoader: ClassLoader) {
        XLog.section("多账号上限提升")
        XLog.i(
            "[多账号] 配置快照：开关=${Prefs.accountEnabled}、上限=${Prefs.maxAccounts}" +
                "（运行期实时读取，改设置切回 Telegram 即生效）"
        )

        hookMaxAccountCount(classLoader)

        var hooked = 0
        var missing = 0
        val detail = StringBuilder()
        for (className in CAPACITY_CLASSES) {
            when (installCapacityHook(classLoader, className)) {
                CapResult.OK -> {
                    hooked++
                    detail.append(className.substringAfterLast('.')).append(' ')
                }
                CapResult.NO_FIELD, CapResult.NO_METHOD -> missing++
                CapResult.CLASS_MISSING -> Unit
                CapResult.ERROR -> Unit
            }
        }
        XLog.result("多账号", "容量保护生效 $hooked 个类：$detail")
        if (missing > 0) {
            XLog.w("[多账号] 有 $missing 个类未匹配到 Instance[] 模式（可能版本差异，通常无害）")
        }

        // 重写依赖 MAX_ACCOUNT_COUNT 常量循环的方法
        hookConstantBoundMethods(classLoader)
    }

    // ------------------------------------------------------------------
    // 1. UI 层账号上限
    // ------------------------------------------------------------------

    private fun hookMaxAccountCount(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(CLS_USER_CONFIG, classLoader)
        if (cls == null) {
            XLog.e("[多账号] 未找到 $CLS_USER_CONFIG，作用域是否勾选了 Telegram？")
            return
        }
        val targets = HookFinder.matchByKey(cls, "account.maxCount")
        if (targets.isEmpty()) {
            XLog.e("[多账号] 未定位到 getMaxAccountCount，多账号上限提升不可用")
            HookStatus.markUnavailable(Prefs.ENABLE_ACCOUNT)
            return
        }

        safe("getMaxAccountCount") {
            for (method in targets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 这里必须包 guard：`param.result` 的赋值只有在返回类型
                        // 与目标方法一致时才合法，而「返回 int」这个前提是靠
                        // HookFinder 的 returnType 过滤来的。哪天 TG 把签名改成
                        // 返回 long，这里就会抛 —— 包了 guard 它会落进
                        // 「回调异常」计数、在「运行状态」里直接看到；
                        // 不包则只是框架日志里一句没有模块前缀的异常，无从归因。
                        guard("账号上限") {
                            if (param.args.isNotEmpty()) return@guard
                            HookStats.hit("account.maxCount")
                            if (!Prefs.accountEnabled) return@guard
                            param.result = Prefs.maxAccounts
                        }
                    }
                })
            }
            XLog.result(
                "多账号",
                "UserConfig.getMaxAccountCount() 已接管 ${targets.size} 处（开启后返回设定值）"
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Instance[] 按需扩容
    // ------------------------------------------------------------------

    private enum class CapResult { OK, CLASS_MISSING, NO_FIELD, NO_METHOD, ERROR }

    private fun installCapacityHook(classLoader: ClassLoader, className: String): CapResult {
        // 关键：initialized = false。若在此处触发静态初始化，
        // 会把整条 Manager 链连带拉起（读库、起线程），代价极高且可能引发启动问题。
        val cls: Class<*> = try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            return CapResult.CLASS_MISSING
        }

        val field = findInstanceArrayField(cls) ?: return CapResult.NO_FIELD
        val method = findIntGetInstance(cls) ?: return CapResult.NO_METHOD

        return try {
            HookInstaller.hookMethodQuietly(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guard("账号扩容") {
                        if (!Prefs.accountEnabled) return@guard
                        val index = param.args.getOrNull(0) as? Int ?: return@guard
                        if (index < 0) return@guard
                        ensureCapacity(cls, field, index)
                    }
                }
            })
            CapResult.OK
        } catch (t: Throwable) {
            XLog.w("[多账号] ${cls.simpleName} 挂载失败: ${t.message}")
            CapResult.ERROR
        }
    }

    /** 优先取名为 Instance 的静态数组字段，兜底取「静态 + 数组 + 组件类型为自身」的唯一字段。 */
    private fun findInstanceArrayField(cls: Class<*>): Field? {
        val fields = try {
            cls.declaredFields
        } catch (t: Throwable) {
            return null
        }

        val named = fields.firstOrNull { it.name == "Instance" }
        if (named != null && Modifier.isStatic(named.modifiers) && named.type.isArray) {
            named.isAccessible = true
            return named
        }

        return fields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type.isArray && it.type.componentType == cls
        }?.also { it.isAccessible = true }
    }

    private fun findIntGetInstance(cls: Class<*>): Method? =
        try {
            cls.declaredMethods.firstOrNull { m ->
                m.name == "getInstance" &&
                    Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        } catch (t: Throwable) {
            null
        }

    /**
     * 扩容静态数组。
     *
     * 必须在调用方读取字段**之前**完成替换 —— 好在原 `getInstance` 每次都是
     * `Instance[num]` 现场读静态字段，所以替换后原方法会自然读到新数组，
     * 后续的 `new Xxx(num)` 逻辑保持不变。
     */
    private fun ensureCapacity(cls: Class<*>, field: Field, index: Int) {
        synchronized(cls) {
            val current = try {
                field.get(null) as? Array<*> ?: return
            } catch (t: Throwable) {
                XLog.w("[多账号] 读取 ${cls.simpleName}.${field.name} 失败: ${t.message}")
                return
            }

            val max = Prefs.maxAccounts
            val needed = maxOf(index + 1, max)
            if (current.size >= needed) return

            try {
                // 必须按原组件类型建新数组，否则 field.set 会抛 IllegalArgumentException
                val expanded = java.lang.reflect.Array.newInstance(current.javaClass.componentType!!, needed)
                System.arraycopy(current, 0, expanded, 0, current.size)
                field.set(null, expanded)
                HookStats.hit("account.expand")
                XLog.result("扩容", "${cls.simpleName}.${field.name}: ${current.size} -> $needed (触发 index=$index)")
            } catch (t: Throwable) {
                XLog.e("[多账号] 扩容 ${cls.simpleName}.${field.name} 失败", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 重写被 MAX_ACCOUNT_COUNT 常量写死循环上界的方法
    // ------------------------------------------------------------------

    private fun hookConstantBoundMethods(classLoader: ClassLoader) {
        val userConfig = XposedHelpers.findClassIfExists(CLS_USER_CONFIG, classLoader) ?: return
        val accountInstance = XposedHelpers.findClassIfExists(CLS_ACCOUNT_INSTANCE, classLoader) ?: return

        // getActivatedAccountsCount(): for (a = 0; a < MAX_ACCOUNT_COUNT; a++) —— 上界被内联为常量
        val countTargets = HookFinder.matchByKey(userConfig, "account.activatedCount")
        safe("getActivatedAccountsCount") {
            for (method in countTargets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? = try {
                        if (!Prefs.accountEnabled) invokeOriginal(param) ?: 0
                        else countActivated(accountInstance)
                    } catch (t: Throwable) {
                        XLog.e("[多账号] getActivatedAccountsCount 回调异常", t)
                        0
                    }
                })
            }
            XLog.result("多账号", "getActivatedAccountsCount() 已接管 ${countTargets.size} 处")
        }

        // hasPremiumOnAccounts(): 同样被常量写死，会导致高级账号状态判断不全
        val premiumTargets = HookFinder.matchByKey(userConfig, "account.premiumCheck")
        safe("hasPremiumOnAccounts") {
            for (method in premiumTargets) {
                HookInstaller.hookMethodQuietly(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? = try {
                        if (!Prefs.accountEnabled) {
                            invokeOriginal(param) ?: false
                        } else {
                            hasPremiumOnAnyAccount(accountInstance)
                        }
                    } catch (t: Throwable) {
                        XLog.e("[多账号] hasPremiumOnAccounts 回调异常", t)
                        false
                    }
                })
            }
            XLog.result("多账号", "hasPremiumOnAccounts() 已接管 ${premiumTargets.size} 处")
        }
    }

    /** 遍历所有已配置的账号，是否存在「已登录且是会员」的。 */
    private fun hasPremiumOnAnyAccount(accountInstance: Class<*>): Boolean {
        for (a in 0 until Prefs.maxAccounts) {
            try {
                val instance = XposedHelpers.callStaticMethod(accountInstance, "getInstance", a) ?: continue
                val uc = XposedHelpers.callMethod(instance, "getUserConfig") ?: continue
                val activated = XposedHelpers.callMethod(uc, "isClientActivated") as? Boolean ?: false
                val premium = XposedHelpers.callMethod(uc, "isPremium") as? Boolean ?: false
                if (activated && premium) return true
            } catch (t: Throwable) {
                // 单个账号查询失败不影响整体结果
            }
        }
        return false
    }

    private fun countActivated(accountInstanceClass: Class<*>): Int {
        var count = 0
        for (a in 0 until Prefs.maxAccounts) {
            try {
                val instance = XposedHelpers.callStaticMethod(accountInstanceClass, "getInstance", a) ?: continue
                val uc = XposedHelpers.callMethod(instance, "getUserConfig") ?: continue
                if (XposedHelpers.callMethod(uc, "isClientActivated") as? Boolean == true) count++
            } catch (t: Throwable) {
                // 单个账号查询失败不影响整体结果
            }
        }
        return count
    }

    /**
     * 放行：手动执行被替换掉的原方法。
     *
     * 用 [XC_MethodReplacement] 时必须显式回退，否则「开关关闭」也会顺手把原逻辑吃掉。
     */
    private fun invokeOriginal(param: MethodHookParam): Any? =
        try {
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
        } catch (t: Throwable) {
            XLog.e("回退原方法失败 (${param.method.name}): ${t.message}")
            null
        }
}
