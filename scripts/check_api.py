#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验项目内部工具类的调用参数名是否合法。

本机没有 Kotlin 编译器，`HookFinder.match(... paramCount = 1)` 这种
「方法没这个参数」的错误只能在 CI 暴露，一轮几分钟。

### v N2.6：参数白名单不再手写，改为从源码推导

原来那张表是手工维护的。N2.1 加了 `HookFinder.matchByKey` 却忘了同步白名单 ——
方法进了仓库、白名单留在原地，两边各自走，检查形同虚设。
这已经是本项目**第三次**栽在「同一份信息两处维护」上（前两次：挂载点 vs
自检清单、自检判定 vs HookFinder 过滤条件）。

现在的做法：扫描源码里 `object Xxx { fun yyy(a: T, b: T) }` 的定义，
自动生成「`Xxx.yyy` -> 参数名集合」。**新方法加进来自动生效**，
不需要有人记得同步白名单。

只覆盖「自己写的、参数名固定」的工具函数 —— 标准库不查。
退出码：0 无问题；1 有问题。
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"

# 需要「必须按名字传」的方法才列在这里（默认上限 = 该方法的参数个数，
# 即只检出「传得比定义还多」的情况）。一般不填。
POSITIONAL_LIMIT_OVERRIDE = {
    # "HookStats.hit": 1,
}

# 推导不出、或推导结果需要修正的极少数情况。
# 空集合表示「这个方法不按名字传，跳过参数名检查」（如 vararg）。
API_OVERRIDE = {
    # "Xxx.yyy": {"a", "b"},
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


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def strip_templates(src):
    """去掉 Kotlin 字符串模板 `${...}`（内部可能还嵌着引号）。"""
    out = []
    i = 0
    while i < len(src):
        if src.startswith("${", i):
            depth, j = 0, i + 1          # i+1 指向 '{'
            while j < len(src):
                if src[j] == "{":
                    depth += 1
                elif src[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            out.append(" ")
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def strip_strings(src):
    """
    把字符串字面量掏空。

    不处理的话会有两类误报：
    1. 日志插值被当成命名参数 ——
       `XLog.i("完成, modulePath=${x}")` 里的 `modulePath=` 会被判成
       `XLog.i(modulePath = ...)`，而 XLog.i 没这个参数。
    2. 模板里嵌套引号 ——
       `XLog.w("...：${list.joinToString("、")}")` 会让简单的引号配对
       正则切错，把一行变成两个「参数」。

    （v N2.6 自动推导后才暴露：推导把 XLog 也纳了进来，
      旧白名单只覆盖少数几个方法，恰好避开了这些日志行。）
    """
    src = strip_templates(src)
    src = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', src, flags=re.S)
    return re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)


def iter_kt(root):
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def parse_param_names(params_str):
    """从参数定义串里取出每个参数的名字。"""
    names = []
    for part in split_top_level(params_str):
        part = part.split("=", 1)[0]           # 去掉默认值
        part = re.sub(r"\bvararg\b", "", part).strip()
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)", part)
        if m:
            names.append(m.group(1))
    return names


def build_api():
    """
    从源码推导 {`Obj.method`: 参数名集合}。

    `fun` 归属于它前面最近的 `object` 声明 —— 本项目里工具类都是
    `object`，归属错了最多让白名单宽松一点，不会误报。
    """
    api = {}
    for path in iter_kt(ROOT):
        src = strip_strings(strip_comments(open(path, encoding="utf-8").read()))
        objects = [(m.start(), m.group(1))
                   for m in re.finditer(r"\bobject\s+([A-Za-z_]\w*)", src)]
        if not objects:
            continue
        for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", src):
            owner = None
            for pos, name in objects:
                if pos < m.start():
                    owner = name
                else:
                    break
            if owner is None:
                continue
            args = extract_args(src, m.end())
            api.setdefault(f"{owner}.{m.group(1)}", set()).update(
                parse_param_names(args)
            )
    for key, names in API_OVERRIDE.items():
        api[key] = set(names)
    return api


API = build_api()

print("=" * 60)
print(f"从源码推导出 {len(API)} 个方法签名")
if "--verbose" in sys.argv:
    for key in sorted(API):
        print(f"    {key}({', '.join(sorted(API[key]))})")

problems = 0
for dirpath, _dirs, files in os.walk(ROOT):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        src = strip_strings(strip_comments(open(path, encoding="utf-8").read()))

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
                limit = POSITIONAL_LIMIT_OVERRIDE.get(api, len(allowed))
                if positional > limit:
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
