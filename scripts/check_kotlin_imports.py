#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 Kotlin 源码里 Xposed / 项目内符号的使用是否都配了 import。

漏 import 这类错误本机没有编译器发现不了，只能等 CI 报错 —— 一轮 CI 就是几分钟。
这个脚本把「用了但没 import」一次性列出来。

v N2.5 起本脚本随仓库走，CI 会在编译前跑它。
退出码：0 无问题；1 有问题。
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"

# 需要显式 import 的符号 -> 期望的 import 前缀
SYMBOLS = {
    "XC_MethodHook": "de.robv.android.xposed",
    "XC_MethodReplacement": "de.robv.android.xposed",
    "XposedBridge": "de.robv.android.xposed",
    "XposedHelpers": "de.robv.android.xposed",
    "XC_LoadPackage": "de.robv.android.xposed.callbacks",
    "IXposedHookLoadPackage": "de.robv.android.xposed",
    "IXposedHookZygoteInit": "de.robv.android.xposed",
    "XSharedPreferences": "de.robv.android.xposed",
    "AndroidAppHelper": "android.app",
    "HookFinder": "com.yjp.tgenhance.hooks",
    "HookInstaller": "com.yjp.tgenhance.hooks",
    "Features": "com.yjp.tgenhance.core",
    "FeatureGroup": "com.yjp.tgenhance.core",
    "RiskLevel": "com.yjp.tgenhance.core",
    "HookStats": "com.yjp.tgenhance.diag",
    "DiagProtocol": "com.yjp.tgenhance.diag",
    "Diagnostics": "com.yjp.tgenhance.diag",
    "DiagBridge": "com.yjp.tgenhance.diag",
    "TgSwitch": "com.yjp.tgenhance.ui",
    "GradientDrawable": "android.graphics.drawable",
    "LayerDrawable": "android.graphics.drawable",
    "RippleDrawable": "android.graphics.drawable",
    "ColorStateList": "android.content.res",
    "ClipData": "android.content",
    "ClipboardManager": "android.content",
    "BroadcastReceiver": "android.content",
    "IntentFilter": "android.content",
    "Intent": "android.content",
    "Handler": "android.os",
    "Bundle": "android.os",
    "Typeface": "android.graphics",
    "FrameLayout": "android.widget",
    "ImageView": "android.widget",
    "LinearLayout": "android.widget",
    "ScrollView": "android.widget",
    "SeekBar": "android.widget",
    "TextView": "android.widget",
    "Toast": "android.widget",
    "AlertDialog": "android.app",
    "Activity": "android.app",
    "Context": "android.content",
    "ValueAnimator": "android.animation",
    "MotionEvent": "android.view",
    "DecelerateInterpolator": "android.view.animation",
    "ViewConfiguration": "android.view",
    "UUID": "java.util",
    "Locale": "java.util",
    "Field": "java.lang.reflect",
    "Method": "java.lang.reflect",
    "Modifier": "java.lang.reflect",
    "ConcurrentHashMap": "java.util.concurrent",
    "AtomicInteger": "java.util.concurrent.atomic",
    "SystemClock": "android.os",
    "SimpleDateFormat": "java.text",
}

problems = 0
for dirpath, _dirs, files in os.walk(ROOT):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        src = open(path, encoding="utf-8").read()
        pkg = re.search(r"^package\s+([\w.]+)", src, re.M)
        pkg = pkg.group(1) if pkg else ""
        # 先去注释再去 import 行：注释里提到的类名不算「使用了」
        code = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        # 字符串字面量里的类名也不算「使用了」——典型的是
        # XposedHelpers.findClassIfExists("android.app.Activity", loader)，
        # 里面的 Activity 只是个类名字符串，不需要 import。
        # 先剥三引号再去单/双引号，避免把三引号的边界当成普通引号。
        code = re.sub(r'"""(?:.|\n)*?"""', '""', code)
        code = re.sub(r"'''(?:.|\n)*?'''", "''", code)
        code = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', code)
        code = re.sub(r"'(?:\\.|[^'\\\n])*'", "''", code)
        code = "\n".join(
            line for line in code.splitlines() if not line.strip().startswith("import ")
        )
        for symbol, pkg_prefix in SYMBOLS.items():
            # (?<![\w.]) 而不是 \b：\b 会把全限定名 android.app.Activity 里的
            # "Activity" 也算成「使用了该符号」，于是写成全限定名的地方被误报成缺 import。
            # 前面紧跟着点号的说明它已经是限定名，Kotlin 不需要 import。
            if not re.search(r"(?<![\w.])" + symbol + r"\b", code):
                continue
            # 同包内的符号不需要 import
            if pkg == pkg_prefix:
                continue
            # import 精确类名，或以该符号结尾
            if re.search(r"^import\s+" + re.escape(pkg_prefix) + r"(\.\w+)*\." + symbol + r"\s*$", src, re.M):
                continue
            if re.search(r"^import\s+" + re.escape(pkg_prefix) + r"\." + symbol + r"\.", src, re.M):
                continue  # import 了它的嵌套成员，如 XC_MethodHook.MethodHookParam
            print(f"MISSING IMPORT  {path}")
            print(f"    {symbol}  ->  应加: import {pkg_prefix}.{symbol}")
            problems += 1

print("=" * 60)
print("缺失 import 数量:", problems)
sys.exit(1 if problems > 0 else 0)
