#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验项目内部工具类的调用参数名是否合法。

本机没有 Kotlin 编译器，`HookFinder.match(... paramCount = 1)` 这种
「方法没这个参数」的错误只能在 CI 暴露，一轮几分钟。
这个脚本把项目内自定义 API 的参数白名单硬编码下来，做一次静态比对。

只覆盖「自己写的、参数名固定」的几个工具函数 —— 标准库不查。

v N2.5 起本脚本随仓库走（原来放在本机临时目录），CI 会在编译前跑它。
退出码：0 无问题；1 有问题（白名单落后于代码时记得同步这里）。
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"

# 工具方法 -> 允许的参数名集合
API = {
    "HookFinder.match": {"cls", "explicitNames", "returnType", "namePrefix", "nameContains", "paramCount", "minParamCount", "maxParamCount"},
    "HookFinder.matchByKey": {"cls", "key"},
    "HookFinder.findMethods": {"cls", "returnType", "namePrefix", "nameContains", "paramCount", "publicOnly"},
    "HookInstaller.hookAllByName": {"cls", "methodName", "callback"},
    "HookInstaller.hookMethodQuietly": {"method", "callback"},
    "HookStats.expect": set(),        # vararg，不按名字传
    "HookStats.hit": {"name"},
    "HookStatus.markUnavailable": {"featureKey"},
    "HookStatus.markGroupFailed": {"group"},
    "ClientProfileDetector.detect": {"classLoader", "packageName"},
    "XLog.timed": {"scope", "block"},
    "XLog.safe": {"scope", "block"},
    "XLog.guard": {"scope", "block"},
    "XLog.result": {"scope", "detail"},
    "XLog.banner": {"pkg", "version"},
}

# 允许省略的位置参数上限（前 N 个可以不写名字）
POSITIONAL_LIMIT = {
    "XLog.result": 2,
    "XLog.banner": 2,
    "XLog.safe": 2,
    "XLog.guard": 2,
    "XLog.timed": 2,
    "HookStats.hit": 1,
    "HookFinder.match": 1,
    "HookFinder.matchByKey": 2,
    "HookFinder.findMethods": 1,
    "HookInstaller.hookAllByName": 3,
    "HookInstaller.hookMethodQuietly": 2,
    "HookStatus.markUnavailable": 1,
    "HookStatus.markGroupFailed": 1,
    "ClientProfileDetector.detect": 2,
}


def extract_args(text, start):
    """从 start（左括号后一位）开始，返回括号内的完整参数串。"""
    depth = 1
    i = start
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return text[start:i]
        i += 1
    return ""


def split_top_level(args):
    """按顶层逗号切分参数。"""
    parts, depth, current = [], 0, []
    for ch in args:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return [p.strip() for p in parts if p.strip()]


problems = 0
for dirpath, _dirs, files in os.walk(ROOT):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        src = open(path, encoding="utf-8").read()
        # 去注释，避免注释里的示例代码被误判
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        src = re.sub(r"//[^\n]*", "", src)

        for api, allowed in API.items():
            for m in re.finditer(re.escape(api) + r"\s*\(", src):
                args = extract_args(src, m.end())
                parts = split_top_level(args)
                positional = 0
                for part in parts:
                    # 取顶层等号（排除 == / <= / >= / != 与 lambda 里的 ->）
                    em = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", part)
                    if em:
                        pname = em.group(1)
                        if allowed and pname not in allowed:
                            line = src[:m.start()].count("\n") + 1
                            print(f"BAD PARAM  {path}:{line}")
                            print(f"    {api}({pname} = ...)  允许: {sorted(allowed)}")
                            problems += 1
                    else:
                        positional += 1
                limit = POSITIONAL_LIMIT.get(api, 0)
                if positional > limit and allowed:
                    line = src[:m.start()].count("\n") + 1
                    print(f"TOO MANY POSITIONAL  {path}:{line}")
                    print(f"    {api} 收到 {positional} 个位置参数（上限 {limit}）")
                    problems += 1

print("=" * 60)
print("内部 API 调用问题数:", problems)

# ---------- 结构检查：批量补丁最容易造成的破坏 ----------
# 教训：用「替换锚点」的方式插代码时，如果替换串里漏掉了原锚点，
# 会把锚点所在的那半截声明吃掉（v7.3.0 就因此把
# `FeatureSpec(` + `key = Prefs.HIDE_PHONE,` 两行弄丢了，连挂两版）。
print("=" * 60)
ROOT2 = ROOT
for dirpath, _d, files in os.walk(ROOT2):
    for name in files:
        if name != "FeatureSpec.kt":
            continue
        path = os.path.join(dirpath, name)
        src = open(path, encoding="utf-8").read()
        # 只数"实例"形式（换行后跟 key = Prefs.），排除 data class 的定义本身
        decls = len(re.findall(r"FeatureSpec\(\s*\n\s*key = Prefs\.", src))
        keys = len(re.findall(r"key = Prefs\.", src))
        opens, closes = src.count("("), src.count(")")
        braces = (src.count("{"), src.count("}"))
        issues = []
        if decls != keys:
            issues.append(f"FeatureSpec( 有 {decls} 个，但 key = Prefs. 有 {keys} 个")
        if opens != closes:
            issues.append(f"圆括号不平衡 {opens}/{closes}")
        if braces[0] != braces[1]:
            issues.append(f"大括号不平衡 {braces[0]}/{braces[1]}")
        if issues:
            problems += len(issues)
            for it in issues:
                print(f"STRUCTURE  {path}: {it}")
print("结构检查完成，累计问题数:", problems)
sys.exit(1 if problems > 0 else 0)
