#!/usr/bin/env bash
# File: scripts/build-magisk.sh
#
# 把 magisk/ 模板 + daemon/out/<abi>/ 产物打成可直接刷入的 zip。
#
# 前置：先跑 scripts/build-daemon.sh。这个脚本不编译任何东西 ——
# 打包和编译混在一起，出问题时根本分不清是工具链挂了还是打包漏了文件。
#
# 产物：build/entropy-magisk-v<version>.zip
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/magisk"
OUT_DIR="$ROOT/dist"                    # 与 APK 同一个交付目录
STAGE="$ROOT/build/magisk-stage"        # 暂存是中间品，留在被 gitignore 的 build/ 下
ABIS=("arm64-v8a" "armeabi-v7a")
ARTIFACTS=(entropyd entropy-monitor entropy-freezer libentropy_hook.so)

die() { echo "错误: $*" >&2; exit 1; }

[[ -d "$SRC" ]] || die "找不到模块模板目录 $SRC"

VERSION="$(sed -n 's/^version=//p' "$SRC/module.prop" | tr -d '\r')"
[[ -n "$VERSION" ]] || die "module.prop 里没有 version 字段"

# ---------- 校验产物 ----------
missing=0
for abi in "${ABIS[@]}"; do
    for f in "${ARTIFACTS[@]}"; do
        p="$ROOT/daemon/out/$abi/$f"
        if [[ ! -s "$p" ]]; then
            echo "缺少 $p"
            missing=1
        fi
    done
done
[[ "$missing" -eq 0 ]] || die "native 产物不完整，先执行 scripts/build-daemon.sh"

# ---------- 组装暂存目录 ----------
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$SRC/." "$STAGE/"

for abi in "${ABIS[@]}"; do
    mkdir -p "$STAGE/bin/$abi"
    for f in "${ARTIFACTS[@]}"; do
        cp "$ROOT/daemon/out/$abi/$f" "$STAGE/bin/$abi/$f"
    done
done

# eBPF 对象是可选件：没有照常出包，entropyd 运行时自动降级到 schedstat 采样。
# 字节码与 CPU 架构无关，所以只取一份，不按 ABI 复制两遍。
BPF_SRC=""
for abi in "${ABIS[@]}"; do
    if [[ -s "$ROOT/daemon/out/$abi/entropy_sched.bpf.o" ]]; then
        BPF_SRC="$ROOT/daemon/out/$abi/entropy_sched.bpf.o"
        break
    fi
done
if [[ -n "$BPF_SRC" ]]; then
    mkdir -p "$STAGE/bpf"
    cp "$BPF_SRC" "$STAGE/bpf/entropy_sched.bpf.o"
    echo "已包含 eBPF 对象: $BPF_SRC"
else
    echo "提示: 未找到 entropy_sched.bpf.o（可选）。需要它请执行 scripts/build-ebpf.sh"
fi

# Magisk 靠这三个条目识别模块包，缺一个就会在 Recovery 里报
# "zip does not appear to be a Magisk module"
for req in META-INF/com/google/android/updater-script \
           META-INF/com/google/android/update-binary \
           module.prop customize.sh; do
    [[ -f "$STAGE/$req" ]] || die "包内缺少 $req"
done

# ---------- 打包 ----------
PY="$(command -v python3 || command -v python || true)"
[[ -n "$PY" ]] || die "需要 python3 或 python 来生成 zip（Git Bash 不自带 zip）"
# Windows 控制台默认不是 UTF-8，不设这个中文输出会变乱码
export PYTHONIOENCODING=utf-8

OUT_ZIP="$OUT_DIR/entropy-magisk-v$VERSION.zip"
"$PY" "$ROOT/scripts/zip_module.py" "$STAGE" "$OUT_ZIP"

# ---------- 回读校验 ----------
# 打完就验：条目名必须是 POSIX 斜杠、脚本必须是 LF。
# 这两点错了在手机上只会表现成"刷机进度条走完但模块列表是空的"，
# 从设备端排查成本极高，所以在出包这一步先自己拆开看一遍。
"$PY" - "$OUT_ZIP" <<'PYEOF'
import sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
bad = []
for zi in zf.infolist():
    if "\\" in zi.filename or zi.filename.startswith("/"):
        bad.append("非法条目名: %s" % zi.filename)
    if zi.filename.endswith((".sh", ".prop", ".rule")) or zi.filename.endswith(("updater-script", "update-binary")):
        if b"\r" in zf.read(zi.filename):
            bad.append("含 CR: %s" % zi.filename)
need = {"module.prop", "customize.sh", "service.sh", "uninstall.sh", "sepolicy.rule",
        "META-INF/com/google/android/updater-script",
        "META-INF/com/google/android/update-binary"}
have = {i.filename for i in zf.infolist()}
miss = need - have
if miss:
    bad.append("缺文件: %s" % ", ".join(sorted(miss)))
for abi in ("arm64-v8a", "armeabi-v7a"):
    for f in ("entropyd", "entropy-monitor", "entropy-freezer", "libentropy_hook.so"):
        n = "bin/%s/%s" % (abi, f)
        if n not in have or zf.getinfo(n).file_size < 64:
            bad.append("产物缺失或过小: %s" % n)
if bad:
    print("\n".join("  ! " + b for b in bad))
    sys.exit(1)
mode = {i.filename: (i.external_attr >> 16) & 0o777 for i in zf.infolist()}
for n in ("customize.sh", "service.sh", "uninstall.sh",
          "META-INF/com/google/android/update-binary"):
    if mode[n] & 0o100 == 0:
        sys.exit("%s 没有可执行位（0%o）" % (n, mode[n]))
print("包校验通过：%d 个条目" % len(have))
PYEOF

cat <<EOF

完成：$OUT_ZIP

刷入方式（任选其一）：
  1) Magisk App -> 模块 -> 从本地安装 -> 选这个 zip
  2) adb push "$OUT_ZIP" /data/local/tmp/ && adb shell 'su -c "magisk --install-module /data/local/tmp/entropy-magisk-v$VERSION.zip"'
刷完必须重启：service.sh 只在 post-fs-data 之后的启动阶段执行。
卸载：Magisk App 里删模块，或 magisk --remove-module entropy。
EOF
