#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 Hook 点的定位是否**一律走注册表**（v N2.1 起）。

### 背景

N2.0 建了 `HookRegistry`：每个 Hook 点的类、方法名、过滤条件都声明在那里，
自检从它派生。N2.1 又把各 `*Hooks.kt` 的定位调用统一成
`HookFinder.matchByKey(cls, "key")`。

于是「注册表里写的」与「挂载时用的」天然一致 —— 但**前提是没人绕过它**。
本脚本就查这件事：

### 检查 1：没有裸的 `HookFinder.match(...)` 调用

直接调用 `match(cls, explicitNames = listOf(...), ...)` 等于把条件又写了一份，
注册表就管不到它了。这类调用应当为零。

### 检查 2：`matchByKey` 用到的 key 都真实存在

key 拼错时 `matchByKey` 会安静地返回空列表（调用方走「功能不可用」分支），
功能没了但不会有任何报错。所以要在发版前查出来。

用法：
    python check_selfcheck_coverage.py --root <项目根>

退出码：0 通过；1 有问题。
"""

import argparse
import glob
import os
import re
import sys

# 定位相关：这些文件里出现裸 match 调用就是问题
HOOKS_GLOB = "app/src/main/java/**/hooks/*.kt"

RAW_MATCH_RE = re.compile(r"HookFinder\.match\(")
BY_KEY_RE = re.compile(r'HookFinder\.matchByKey\(\s*[\w.]+\s*,\s*"([^"]+)"')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="项目根目录")
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    hooks_files = sorted(glob.glob(os.path.join(root, HOOKS_GLOB), recursive=True))
    if not hooks_files:
        raise SystemExit(f"在 {root} 下没找到 hooks/*.kt")

    registry_path = os.path.join(
        root, "app/src/main/java/com/yjp/tgenhance/hooks/HookRegistry.kt"
    )
    if not os.path.isfile(registry_path):
        raise SystemExit("找不到 HookRegistry.kt")

    # 注册表里声明的 key
    registry = open(registry_path, encoding="utf-8").read()
    declared = set(re.findall(r'^\s+key = "([^"]+)",', registry, re.M))

    failures = []

    # ---- 检查 1：裸 match 调用 ----
    raw_hits = []
    for path in hooks_files:
        name = os.path.basename(path)
        if name == "HookFinder.kt":
            # 定义处自己不算
            continue
        for i, line in enumerate(open(path, encoding="utf-8").read().splitlines()):
            if RAW_MATCH_RE.search(line):
                raw_hits.append(f"{name}:{i + 1}")

    if raw_hits:
        failures.append(
            "以下位置仍直接调用 HookFinder.match(...)，绕过了注册表：\n"
            + "\n".join(f"      ✗ {h}" for h in raw_hits)
        )

    # ---- 检查 2：matchByKey 的 key 有效性 ----
    used = {}
    for path in hooks_files:
        name = os.path.basename(path)
        for m in BY_KEY_RE.finditer(open(path, encoding="utf-8").read()):
            used.setdefault(m.group(1), name)

    unknown = {k: v for k, v in used.items() if k not in declared}

    print("=" * 70)
    print(f"注册表声明 {len(declared)} 个 Hook 点，代码里按 key 定位 {len(used)} 处")

    if raw_hits:
        print(f"[1/2] 绕过注册表的直接调用：{len(raw_hits)} 处")
        for h in raw_hits:
            print(f"      ✗ {h}")
    else:
        print("[1/2] 无绕过注册表的直接调用 ✓")

    if unknown:
        print(f"[2/2] 用了注册表里没有的 key：{len(unknown)} 处")
        for k, src in sorted(unknown.items()):
            print(f"      ✗ \"{k}\"（来自 {src}）")
    else:
        print("[2/2] 所有 matchByKey 的 key 都在注册表里 ✓")

    # 反向提示：注册表里声明了、但既没用 matchByKey、也没用 hookAllByName 的 key
    #
    # 注意要一并扫 hookAllByName —— 有几类 Hook 点本来就是按名字挂全部重载的
    # （private 方法、需要挂多个重载的方法），不经过 matchByKey。
    # 只看 matchByKey 会把这批全部误报成「没接线」。
    by_name = set()
    for path in hooks_files:
        for m in re.finditer(
            r'HookInstaller\.hookAllByName\(\s*[\w.]+\s*,\s*"([^"]+)"',
            open(path, encoding="utf-8").read(),
        ):
            by_name.add(m.group(1))

    # 注册表里每个 key 对应的方法名
    names_by_key = {}
    for m in re.finditer(r'key = "([^"]+)"[\s\S]*?names = listOf\(([^)]*)\)', registry):
        names_by_key[m.group(1)] = re.findall(r'"([^"]+)"', m.group(2))

    unused = sorted(
        k for k in declared
        if k not in used and not (set(names_by_key.get(k, [])) & by_name)
    )
    if unused:
        print()
        print("提示：以下 key 在注册表里声明了，但代码里既没按 key 定位、")
        print("      也没有同名的 hookAllByName 调用 —— 可能是调用点被删而声明没清：")
        for k in unused:
            print(f"      ? {k}")

    print("=" * 70)
    print("通过" if not failures else f"有 {len(failures)} 项问题")
    for f in failures:
        print(f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
