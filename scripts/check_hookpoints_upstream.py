#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿注册表的 Hook 点去对一遍 Telegram 上游源码，看它们还在不在。

### 为什么

模块的 Hook 点是对着 `DrKLO/Telegram` 的源码定位的。官方一改名，
本模块的对应功能就**静默失效**：编译照过、安装照常，
只是那个功能再也不起作用 —— 而自检因为「方法还在按名字找」也可能不报错。

N1.14 时人工核过一次（16 个点全对得上）。但人工核对做一次就过期了 ——
**TG 每次更新都可能改这些东西**。所以把它做成脚本：
Telegram 升级后跑一次，几分钟就知道哪些点需要跟进。

### 用法

```bash
python3 scripts/check_hookpoints_upstream.py                 # 对 master
python3 scripts/check_hookpoints_upstream.py --ref 11.14.0   # 对某个 tag/分支
python3 scripts/check_hookpoints_upstream.py --warn-only     # 不因失效而失败
```

源码会缓存到 `.cache/tg-src`（重复跑不用重新下载）。
加 `--refresh` 强制重新下载。

### 它不做什么

只查「方法还在不在」，**不判断语义有没有变**。
找到的定义行会打印出来，是否还是同一个意思需要人看一眼 ——
脚本能省掉的是「去哪个文件找、找什么」，省不掉最后那眼确认。

退出码：0 全部健在；1 有失效（除非 `--warn-only`）。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys

REPO = "DrKLO/Telegram"
SRC_PREFIX = "TMessagesProj/src/main/java"
REGISTRY = os.path.join(
    "app", "src", "main", "java", "com", "yjp", "tgenhance", "hooks", "HookRegistry.kt"
)

# Java 方法定义：**从行首**开始（缩进 + 可选注解 + 修饰符 + 返回类型 + 名称 + 左括号）
#
# 锚定行首很关键。不锚的话，`[\w.\s]+?` 会一路往前吃，匹配到
# 方法体里的调用处（打印出来是 `}` 或 `ArrayList<Integer> mids = ...`
# 这类无关行），看着像找到了，其实定位错了地方。
METHOD_DEF = (
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*)*"
    r"(?:public|private|protected|static|final|synchronized|native|abstract|[ \t])*"
    r"[\w.<>\[\],? \t]+?[ \t]+{name}[ \t]*\("
)


def curl_json(url):
    """用 curl 取 JSON。

    不用 urllib：本机在代理下走 Python 的 SSL 握手会超时（下载 Release
    资产时踩过，N1.14 也是换了 curl 才拿到大文件）。
    """
    out = subprocess.run(
        ["curl", "-sL", "-H", "User-Agent: tgenhance-check", url],
        capture_output=True,
    )
    return json.loads(out.stdout.decode("utf-8", "replace"))


def parse_registry(path):
    """从 HookRegistry.kt 里取出（类名, [方法名]）。

    只取 `owner = TargetOwner.FIXED` 的条目 —— 另外三类（主 Activity /
    StoriesController / 设置页）的类名是运行期由客户端档案解析的，
    上游路径不固定，本脚本查不了。
    """
    src = open(path, encoding="utf-8").read()
    entries = []
    for block in src.split("HookTarget(")[1:]:
        m_class = re.search(r'className = "([^"]+)"', block)
        m_names = re.search(r"names = listOf\(([^)]*)\)", block)
        if not m_class or not m_names:
            continue
        if "TargetOwner.FIXED" not in block:
            continue
        names = re.findall(r'"([^"]+)"', m_names.group(1))
        if names:
            entries.append((m_class.group(1), names))
    return entries


def fetch_source(class_name, cache_dir, ref, refresh):
    rel_path = f"{SRC_PREFIX}/{class_name.replace('.', '/')}.java"
    dest = os.path.join(cache_dir, class_name.split(".")[-1] + ".java")
    if os.path.isfile(dest) and not refresh:
        return open(dest, encoding="utf-8", errors="replace").read(), rel_path

    meta = curl_json(
        f"https://api.github.com/repos/{REPO}/contents/{rel_path}?ref={ref}"
    )
    if "sha" not in meta:
        return None, rel_path
    blob = curl_json(f"https://api.github.com/repos/{REPO}/git/blobs/{meta['sha']}")
    if "content" not in blob:
        return None, rel_path
    raw = base64.b64decode(blob["content"])
    os.makedirs(cache_dir, exist_ok=True)
    with open(dest, "wb") as fh:
        fh.write(raw)
    return raw.decode("utf-8", "replace"), rel_path


def first_definition(src, name):
    """返回该方法定义所在的那一行（截断），找不到返回 None。"""
    pattern = re.compile(METHOD_DEF.format(name=re.escape(name)), re.M)
    m = pattern.search(src)
    if not m:
        return None
    start = src.rfind("\n", 0, m.start()) + 1
    end = src.find("\n", m.start())
    return src[start:end if end != -1 else len(src)].strip()[:120]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default="master", help="Telegram 的分支 / tag")
    ap.add_argument("--cache", default=".cache/tg-src", help="源码缓存目录")
    ap.add_argument("--refresh", action="store_true", help="忽略缓存重新下载")
    ap.add_argument("--warn-only", action="store_true", help="失效时不返回失败")
    args = ap.parse_args()

    if not os.path.isfile(REGISTRY):
        print(f"找不到 {REGISTRY}（请在仓库根目录运行）")
        return 2

    entries = parse_registry(REGISTRY)
    print(f"注册表里 {len(entries)} 个固定类 Hook 点，对照 {REPO}@{args.ref}")
    print("=" * 70)

    missing = 0
    checked = 0
    for class_name, names in entries:
        src, rel = fetch_source(class_name, args.cache, args.ref, args.refresh)
        short = class_name.split(".")[-1]
        if src is None:
            print(f"[?] {short}: 取不到源码（{rel}）—— 路径可能变了")
            missing += len(names)
            continue
        for name in names:
            checked += 1
            line = first_definition(src, name)
            if line:
                print(f"[OK] {short}.{name}")
                print(f"       {line}")
            else:
                print(f"[!!] {short}.{name} —— 上游找不到这个方法（改名了？）")
                missing += 1

    print("=" * 70)
    print(f"检查 {checked} 个方法，失效 {missing} 个")
    if missing:
        print()
        print("失效不等于立刻不能用（有些客户端是 fork，改得比上游慢），")
        print("但要跟进：确认新名字后更新 HookRegistry，并让自检重新覆盖。")
        return 0 if args.warn_only else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
