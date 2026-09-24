package com.yjp.tgenhance.hooks

import com.yjp.tgenhance.Prefs
import com.yjp.tgenhance.XLog
import com.yjp.tgenhance.XLog.safe
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
        if (!Prefs.accountEnabled) {
            XLog.i("[多账号] 开关关闭，跳过")
            return
        }

        val max = Prefs.maxAccounts
        XLog.section("多账号上限提升 -> $max")

        hookMaxAccountCount(classLoader, max)

        var hooked = 0
        var missing = 0
        val detail = StringBuilder()
        for (className in CAPACITY_CLASSES) {
            when (installCapacityHook(classLoader, className, max)) {
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
        hookConstantBoundMethods(classLoader, max)
    }

    // ------------------------------------------------------------------
    // 1. UI 层账号上限
    // ------------------------------------------------------------------

    private fun hookMaxAccountCount(classLoader: ClassLoader, max: Int) {
        val cls = XposedHelpers.findClassIfExists(CLS_USER_CONFIG, classLoader)
        if (cls == null) {
            XLog.e("[多账号] 未找到 $CLS_USER_CONFIG，作用域是否勾选了 Telegram？")
            return
        }
        safe("getMaxAccountCount") {
            XposedBridge.hookAllMethods(cls, "getMaxAccountCount", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) param.result = max
                }
            })
            XLog.result("多账号", "UserConfig.getMaxAccountCount() 恒定返回 $max（原始：免费 3 / 会员 5）")
        }
    }

    // ------------------------------------------------------------------
    // 2. Instance[] 按需扩容
    // ------------------------------------------------------------------

    private enum class CapResult { OK, CLASS_MISSING, NO_FIELD, NO_METHOD, ERROR }

    private fun installCapacityHook(classLoader: ClassLoader, className: String, max: Int): CapResult {
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val index = param.args.getOrNull(0) as? Int ?: return
                    if (index < 0) return
                    ensureCapacity(cls, field, index, max)
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
    private fun ensureCapacity(cls: Class<*>, field: Field, index: Int, max: Int) {
        synchronized(cls) {
            val current = try {
                field.get(null) as? Array<*> ?: return
            } catch (t: Throwable) {
                XLog.w("[多账号] 读取 ${cls.simpleName}.${field.name} 失败: ${t.message}")
                return
            }

            val needed = maxOf(index + 1, max)
            if (current.size >= needed) return

            try {
                // 必须按原组件类型建新数组，否则 field.set 会抛 IllegalArgumentException
                val expanded = java.lang.reflect.Array.newInstance(current.javaClass.componentType!!, needed)
                System.arraycopy(current, 0, expanded, 0, current.size)
                field.set(null, expanded)
                XLog.result("扩容", "${cls.simpleName}.${field.name}: ${current.size} -> $needed (触发 index=$index)")
            } catch (t: Throwable) {
                XLog.e("[多账号] 扩容 ${cls.simpleName}.${field.name} 失败", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 重写被 MAX_ACCOUNT_COUNT 常量写死循环上界的方法
    // ------------------------------------------------------------------

    private fun hookConstantBoundMethods(classLoader: ClassLoader, max: Int) {
        val userConfig = XposedHelpers.findClassIfExists(CLS_USER_CONFIG, classLoader) ?: return
        val accountInstance = XposedHelpers.findClassIfExists(CLS_ACCOUNT_INSTANCE, classLoader) ?: return

        // getActivatedAccountsCount(): for (a = 0; a < MAX_ACCOUNT_COUNT; a++) —— 上界被内联为 4
        safe("getActivatedAccountsCount") {
            XposedBridge.hookAllMethods(userConfig, "getActivatedAccountsCount", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any =
                    countActivated(accountInstance, max)
            })
            XLog.result("多账号", "getActivatedAccountsCount() 已扩展到 $max 个账号范围")
        }

        // hasPremiumOnAccounts(): 同样被常量写死，会导致高级账号状态判断不全
        safe("hasPremiumOnAccounts") {
            XposedBridge.hookAllMethods(userConfig, "hasPremiumOnAccounts", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    for (a in 0 until max) {
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
            })
            XLog.result("多账号", "hasPremiumOnAccounts() 已扩展到 $max 个账号范围")
        }
    }

    private fun countActivated(accountInstanceClass: Class<*>, max: Int): Int {
        var count = 0
        for (a in 0 until max) {
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
}
