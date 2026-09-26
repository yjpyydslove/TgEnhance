#!/usr/bin/env bash
#
# 本地一键跑全部检查（v N2.9）。
#
# CI 里是分两段跑的 —— 源码检查在编译前（几秒就能失败，不用等编译），
# 产物自检在编译后（得先有 APK）。这里合在一起，方便本机改完自查。
#
# 用法：
#   bash scripts/check_all.sh
#
set -e
cd "$(dirname "$0")/.."

PY="${PY:-python3}"

echo "=========== 源码层（编译前）==========="
"$PY" scripts/check_kotlin_imports.py app/src/main/java
"$PY" scripts/check_api.py .
"$PY" scripts/check_hook_guard.py app/src/main/java
"$PY" scripts/check_selfcheck_coverage.py --root .
"$PY" scripts/check_registry_consistency.py --root .

echo ""
echo "=========== 产物层（编译后）==========="
APK="$(find app/build/outputs/apk/release -name '*.apk' -print -quit 2>/dev/null || true)"
if [ -n "$APK" ]; then
    "$PY" scripts/check_apk.py "$APK"
else
    echo "（本地没有 APK，跳过产物自检 —— CI 上每次都会跑）"
fi

echo ""
echo "全部检查通过"
