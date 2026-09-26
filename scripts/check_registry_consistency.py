#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查三张「注册表」之间的引用是否一致。

这三项以前都是临时敲命令查的，N1.17 固化成脚本 —— 发版前跑一遍就够。

### 检查 1：统计点声明 vs 实际打点

`HookCatalog` 声明了所有应该被计数的 Hook 点，代码里用
`HookStats.hit("key")` 打点。两边对不上时：

- 声明了但从不 hit -> 界面「运行状态」里那一行永远是 0，用户以为功能没生效
- hit 了但没声明 -> 界面显示原始的 `privacy.foo` 这种 key，看不懂

### 检查 2：上报用的 key 是否是真实存在的功能

`HookStatus.markUnavailable(Prefs.XXX)` 用功能 key 登记「当前客户端不支持」。
传了一个不在 `Features.ALL` 里的 key，界面回显时查不到标题，
只能显示配置 key 本身。

### 检查 3：开关是否真的被读取

`Features.ALL` 里的每个布尔开关都该有对应的 `Prefs.xxx` 属性，
并且 hook 端真的读过它。**从没被读过的开关就是「摆设」** ——
界面上能拨，拨了没有任何效果。

（`HIDE_LAUNCHER_ICON` 是已知例外：它只作用于模块自己的界面进程，
hook 端本就不该读。）

用法：
    python check_registry_consistency.py --root <项目根>

退出码：0 全部一致；1 有发现问题。
"""

import argparse
import glob
import os
import re
import sys

# 检查 3 的已知例外：只作用于模块界面、hook 端不需要读的开关
HOOK_SIDE_EXEMPT = {"HIDE_LAUNCHER_ICON"}


def read(path):
    with open(path, encoding="utf-8") as fp:
        return fp.read()


def find_src(root):
    return sorted(glob.glob(os.path.join(root, "app/src/main/java/**/*.kt"), recursive=True))


def check_statkeys(root, files):
    """检查 1：HookCatalog 声明 vs HookStats.hit 调用。"""
    catalog = os.path.join(
        root, "app/src/main/java/com/yjp/tgenhance/hooks/HookCatalog.kt"
    )
    if not os.path.isfile(catalog):
        return None, "找不到 HookCatalog.kt"

    declared = set(re.findall(r'HookPoint\("([^"]+)"', read(catalog)))
    hit = set()
    for path in files:
        hit |= set(re.findall(r'HookStats\.hit\("([^"]+)"', read(path)))

    only_declared = sorted(declared - hit)
    only_hit = sorted(hit - declared)
    problems = []
    if only_declared:
        problems.append(f"声明了但从未打点（界面永远显示 0）：{', '.join(only_declared)}")
    if only_hit:
        problems.append(f"打点了但未声明（界面会显示原始 key）：{', '.join(only_hit)}")
    return (len(declared), len(hit), problems), None


def check_unavailable_keys(root, files):
    """检查 2：markUnavailable 用的 key 是否都在 Features.ALL 里。"""
    feature_file = os.path.join(
        root, "app/src/main/java/com/yjp/tgenhance/core/FeatureSpec.kt"
    )
    if not os.path.isfile(feature_file):
        return None, "找不到 FeatureSpec.kt"

    known = set(re.findall(r"key = Prefs\.([A-Z_]+)", read(feature_file)))
    used = set()
    for path in files:
        used |= set(re.findall(r"markUnavailable\(Prefs\.([A-Z_]+)\)", read(path)))

    unknown = sorted(used - known)
    problems = []
    if unknown:
        problems.append(f"上报用了注册表里没有的 key：{', '.join(unknown)}")
    return (len(used), len(known), problems), None


def check_switches_read(root, files):
    """检查 3：Features.ALL 的布尔开关是否被 hook 端读过。"""
    prefs_file = os.path.join(root, "app/src/main/java/com/yjp/tgenhance/Prefs.kt")
    feature_file = os.path.join(
        root, "app/src/main/java/com/yjp/tgenhance/core/FeatureSpec.kt"
    )
    for p in (prefs_file, feature_file):
        if not os.path.isfile(p):
            return None, f"找不到 {os.path.basename(p)}"

    prefs = read(prefs_file)
    # 属性名 -> 常量名
    prop2const = dict(
        re.findall(r"val (\w+): Boolean get\(\) = hookBoolean\(([A-Z_]+)", prefs)
    )
    const2prop = {v: k for k, v in prop2const.items()}

    feature_keys = re.findall(r"key = Prefs\.([A-Z_]+)", read(feature_file))

    # hook 端代码：排除模块界面进程（ui/）与 Prefs 自身
    hook_src = "\n".join(
        read(p)
        for p in files
        if "/ui/" not in p.replace(os.sep, "/") and not p.endswith("Prefs.kt")
    )

    problems = []
    checked = 0
    for const in sorted(set(feature_keys)):
        if const in HOOK_SIDE_EXEMPT:
            continue
        prop = const2prop.get(const)
        if prop is None:
            # 没有对应的布尔属性（例如纯数值项），跳过
            continue
        checked += 1
        if not re.search(r"Prefs\." + prop + r"\b", hook_src):
            problems.append(f"{const}（Prefs.{prop}）hook 端从未读取 —— 可能是个摆设开关")
    return (checked, problems), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="项目根目录")
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    files = find_src(root)
    if not files:
        raise SystemExit(f"在 {root} 下没找到 Kotlin 源码")

    failed = 0
    print("=" * 70)

    result, err = check_statkeys(root, files)
    if err:
        print(f"[1/3] 统计点一致性：跳过（{err}）")
        failed += 1
    else:
        declared, hit, problems = result
        if problems:
            print(f"[1/3] 统计点一致性：声明 {declared} 个 / 打点 {hit} 个 —— 有问题")
            for p in problems:
                print(f"      ✗ {p}")
            failed += 1
        else:
            print(f"[1/3] 统计点一致性：声明 {declared} 个 / 打点 {hit} 个 —— 双向对齐 ✓")

    result, err = check_unavailable_keys(root, files)
    if err:
        print(f"[2/3] 上报 key 有效性：跳过（{err}）")
        failed += 1
    else:
        used, known, problems = result
        if problems:
            print(f"[2/3] 上报 key 有效性：用了 {used} 个 —— 有问题")
            for p in problems:
                print(f"      ✗ {p}")
            failed += 1
        else:
            print(f"[2/3] 上报 key 有效性：{used} 个全部在注册表内（共 {known} 项）✓")

    result, err = check_switches_read(root, files)
    if err:
        print(f"[3/3] 开关被读取：跳过（{err}）")
        failed += 1
    else:
        checked, problems = result
        if problems:
            print(f"[3/3] 开关被读取：检查 {checked} 个 —— 有问题")
            for p in problems:
                print(f"      ✗ {p}")
            failed += 1
        else:
            print(f"[3/3] 开关被读取：{checked} 个布尔开关都被 hook 端读过 ✓")

    print("=" * 70)
    print("全部一致" if failed == 0 else f"有 {failed} 项需要处理")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
