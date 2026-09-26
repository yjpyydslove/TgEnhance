#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查每个 Xposed hook 回调是否做了异常防护。

### 为什么需要这个

Xposed 框架自己会捕获回调异常并继续执行原方法，所以回调抛异常**不会崩宿主** ——
但它的日志里看不出这是模块引起的。模块自己再包一层 `guard` 的作用，
是把「回调抛过异常」变成一条带模块前缀、且计入 `internal.callbackError`
统计的记录，于是能在模块设置界面的「运行状态」里直接被用户看到。

漏包的回调不致命，但会让**归因**丢失：用户看到的是「有时功能不生效」，
日志里却什么都没有。

### 有一类回调**必须**不包

隐身类 Hook（拒绝类加载、拒绝包名查询）的任务就是**抛异常给调用方** ——
包了 guard 会把异常吞掉，隐身随之静默失效，而且完全看不出来。
这类回调在代码里用 `刻意不包` 标记，本脚本会识别并跳过。

用法：
    python check_hook_guard.py <源码目录>        # 通常传 app/src/main/java
    python check_hook_guard.py <源码目录> --all  # 连合规项一起列出来
"""

import glob
import os
import re
import sys

MARK = "刻意不包"
CALLBACK_RE = re.compile(r"override\s+fun\s+(?:after|before)HookedMethod")


def extract_body(lines, start):
    """从回调定义行开始，按花括号配对取出函数体。"""
    depth = 0
    started = False
    body = []
    for j in range(start, min(start + 120, len(lines))):
        line = lines[j]
        body.append(line)
        depth += line.count("{") - line.count("}")
        if "{" in line:
            started = True
        if started and depth <= 0:
            break
    return body


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show_all = "--all" in sys.argv
    if not args:
        raise SystemExit("用法: python check_hook_guard.py <源码目录> [--all]")

    root = args[0]
    files = glob.glob(os.path.join(root, "**", "*.kt"), recursive=True)
    if not files:
        raise SystemExit(f"在 {root} 下没找到 .kt 文件")

    total = guarded = marked = 0
    violations = []

    for path in sorted(files):
        with open(path, encoding="utf-8") as fp:
            lines = fp.read().splitlines()
        for i, line in enumerate(lines):
            if not CALLBACK_RE.search(line):
                continue
            total += 1
            body = extract_body(lines, i)
            text = "\n".join(body)
            # 标记可以写在函数体内，也可以写在定义行上方几行
            context = "\n".join(lines[max(0, i - 4):i + 1])
            has_guard = "guard(" in text
            has_mark = MARK in text or MARK in context

            name = os.path.relpath(path, root).replace(os.sep, "/")
            if has_guard:
                guarded += 1
                if show_all:
                    print(f"  [guard]  {name}:{i + 1}")
            elif has_mark:
                marked += 1
                if show_all:
                    print(f"  [marked] {name}:{i + 1}  （刻意不包，已标注）")
            else:
                violations.append(f"{name}:{i + 1}")

    print("=" * 64)
    print(f"回调总数 {total}：guard {guarded}、刻意不包 {marked}、未防护 {len(violations)}")
    print("=" * 64)

    if violations:
        print("以下回调既没有 guard，也没有「刻意不包」标记：")
        for v in violations:
            print(f"  ✗ {v}")
        print()
        print("处理方式二选一：")
        print("  · 包一层 guard（回调里只是读配置、写 param.result 的，都该包）")
        print("  · 若这个回调确实要靠抛异常来工作，加一行 `// 刻意不包 guard：<原因>`")
        return 1

    print("全部回调都有防护或已标注 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
