#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查编译产物 APK 是否真的能被 LSPosed 识别。

### 为什么需要

「编译成功」不等于「装上去能用」。有几种失败是**编译时一点迹象都没有**的：
APK 能编出来、能安装、但 LSPosed 的模块列表里**根本不出现它** ——
用户只会觉得「装了没反应」，而日志里什么都没有。

本脚本在打完包之后、发布之前，把这几项静态查一遍：

1. `assets/xposed_init` 是否存在且非空（LSPosed 靠它找入口类）
2. `xposed_init` 里写的入口类是否真的在 dex 里（写错类名是最常见的一种）
3. `AndroidManifest.xml` 里是否有 xposed 模块的三项元数据
4. 是否含 `classes*.dex`

### 用法

```bash
python3 scripts/check_apk.py app/build/outputs/apk/release/TgEnhance-*.apk
```

退出码：0 全部通过；1 有问题。
"""
import re
import sys
import zipfile

# LSPosed 识别模块所需的 manifest 元数据
REQUIRED_META = ("xposedmodule", "xposedminversion", "xposeddescription")


def read(zf, name):
    try:
        return zf.read(name)
    except KeyError:
        return None


def has_meta(manifest_bytes, key):
    """在（二进制 AXML 的）manifest 里找元数据名。

    AXML 的字符串池通常是 UTF-16LE，也可能是 UTF-8，两种都试。
    """
    for enc in ("utf-16-le", "utf-8"):
        if key.encode(enc) in manifest_bytes:
            return True
    return False


def main():
    if len(sys.argv) < 2:
        print("用法: check_apk.py <apk 路径>")
        return 2
    apk = sys.argv[1]
    problems = 0

    try:
        zf = zipfile.ZipFile(apk)
    except Exception as e:
        print(f"FATAL  打不开 APK: {e}")
        return 1

    with zf:
        names = zf.namelist()

        # --- 1. xposed_init ---
        init = read(zf, "assets/xposed_init")
        if init is None:
            print("MISSING  assets/xposed_init —— LSPosed 找不到模块入口")
            problems += 1
            entries = []
        else:
            entries = [
                line.strip()
                for line in init.decode("utf-8", "replace").splitlines()
                if line.strip() and not line.strip().startswith("#")
            ]
            if not entries:
                print("EMPTY   assets/xposed_init 里没有有效的入口类名")
                problems += 1
            else:
                print(f"OK      xposed_init 入口 {len(entries)} 个: {', '.join(entries)}")

        # --- 2. 入口类是否真的在 dex 里 ---
        dex_names = [n for n in names if n.endswith(".dex")]
        if not dex_names:
            print("MISSING  没有 classes*.dex")
            problems += 1
            dex_blobs = b""
        else:
            dex_blobs = b"".join(read(zf, n) or b"" for n in dex_names)
            print(f"OK      dex {len(dex_names)} 个（{len(dex_blobs) / 1024:.0f} KB）")

        for cls in entries:
            # dex 里的类名用 '/' 分隔
            needle = cls.replace(".", "/").encode("utf-8")
            if needle not in dex_blobs:
                print(f"BAD     入口类 {cls} 在 dex 里找不到（xposed_init 写错类名？）")
                problems += 1
            else:
                print(f"OK      入口类 {cls} 存在于 dex")

        # --- 3. manifest 元数据 ---
        manifest = read(zf, "AndroidManifest.xml")
        if manifest is None:
            print("MISSING  AndroidManifest.xml")
            problems += 1
        else:
            for key in REQUIRED_META:
                if has_meta(manifest, key):
                    print(f"OK      manifest 含 {key}")
                else:
                    print(f"MISSING manifest 缺少 {key} —— 模块列表里不会出现本模块")
                    problems += 1

    print("=" * 60)
    print("产物自检问题数:", problems)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
