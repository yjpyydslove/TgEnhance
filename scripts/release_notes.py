#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 CHANGELOG.md 里取出某个版本的说明，用作 Release 正文。

### 为什么

Release 页面默认由 GitHub 用 commit 列表自动生成。但本项目的 commit
标题写的是「做了什么」，而 CHANGELOG 里写的是「做了什么**以及为什么**」——
后者才是下载的人想看的。既然已经维护了 CHANGELOG，Release 就别再生成一份
更没信息量的了。

### 用法

```bash
python3 scripts/release_notes.py N2.9            # 输出到 stdout
python3 scripts/release_notes.py N2.9 > notes.md
```

### 找不到该版本时

**直接失败**（退出码 1），而不是回退到自动生成。

因为「发布了却没记录」和「记录里没有这一版」是同一种漂移 ——
静默回退会把它藏起来，等哪天想查「这版到底改了什么」才发现查不到。
（CHANGELOG 缺当前版本时 CI 会红，补上即可。）
"""
import re
import sys

CHANGELOG = "CHANGELOG.md"


def main():
    if len(sys.argv) < 2:
        print("用法: release_notes.py <版本号> [CHANGELOG 路径]")
        return 2
    ver = sys.argv[1].lstrip("v")
    path = sys.argv[2] if len(sys.argv) > 2 else CHANGELOG

    try:
        lines = open(path, encoding="utf-8").read().splitlines()
    except OSError as e:
        print(f"读不了 {path}: {e}")
        return 1

    # 标题形如「### N2.9 —— xxx」或旧版的「### v1.x —— xxx」
    pattern = re.compile(r"^###\s+v?" + re.escape(ver) + r"(?=\s|—|$)")
    start = None
    for i, line in enumerate(lines):
        if pattern.match(line):
            start = i
            break

    if start is None:
        print(
            f"{path} 里没有 {ver} 这一版。\n"
            f"请先补写 CHANGELOG（发布不该没有记录），再重新跑。"
        )
        return 1

    end = len(lines)
    for j in range(start + 1, len(lines)):
        if lines[j].startswith("### ") or lines[j].startswith("## "):
            end = j
            break

    title = lines[start].lstrip("#").strip()
    body = "\n".join(lines[start + 1:end]).strip()

    out = [f"## {title}", ""]
    if body:
        out += [body, ""]
    out += [
        "---",
        "",
        "完整版本历史见 [CHANGELOG.md]"
        "(https://github.com/yjpyydslove/TgEnhance/blob/main/CHANGELOG.md)。",
        "",
    ]
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
