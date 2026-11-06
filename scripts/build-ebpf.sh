#!/usr/bin/env bash
# File: scripts/build-ebpf.sh
#
# 单独编 eBPF 对象 entropy_sched.bpf.o。
#
# 为什么不给 NDK 用：
#   -target bpf 编的是内核字节码，链接的是内核的 BTF 与 libbpf 头，
#   跟 Android 的用户态 ABI 一点关系都没有。NDK sysroot 里没有 bpf/bpf_helpers.h，
#   拿它编只会得到一堆 "file not found"。这一步必须在装了 libbpf-dev 的
#   Linux 宿主机（或 WSL）上跑；Windows/Git Bash 上直接给出可执行的结论，
#   而不是让用户盯着一个看起来像工具链坏了的报错。
#
# 产物：daemon/out/ebpf/entropy_sched.bpf.o，build-magisk.sh 会自动带上它。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/daemon/ebpf/entropy_sched.bpf.c"
OUT_DIR="$ROOT/daemon/out/ebpf"
OUT="$OUT_DIR/entropy_sched.bpf.o"

# 优先用带 bpf 后端的版本化 clang，最后才退回 PATH 上的 clang
CLANG=""
for c in clang-18 clang-17 clang-16 clang-15 clang; do
    if command -v "$c" >/dev/null 2>&1; then CLANG="$c"; break; fi
done
[[ -n "$CLANG" ]] || { echo "错误: PATH 上没有 clang（需要 >= 15 且带 bpf 后端）" >&2; exit 1; }

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "警告: 当前宿主不是 Linux（$(uname -s)）。" >&2
    echo "      eBPF 对象可以编出来，但缺 vmlinux.h 时 CO-RE 重定位不生效，" >&2
    echo "      换内核版本就可能被 verifier 拒绝。建议在 WSL 或原生 Linux 上构建。" >&2
fi

# bpf 头文件搜索路径：包管理器装的 libbpf-dev，或用户显式指的那份
BPF_INC="${BPF_INC_DIR:-}"
if [[ -z "$BPF_INC" ]]; then
    for d in /usr/include /usr/local/include /opt/homebrew/include; do
        [[ -f "$d/bpf/bpf_helpers.h" ]] && { BPF_INC="$d"; break; }
    done
fi
[[ -n "$BPF_INC" ]] || {
    echo "错误: 找不到 bpf/bpf_helpers.h" >&2
    echo "      Debian/Ubuntu: sudo apt install libbpf-dev linux-tools-common" >&2
    echo "      或自行指定:    BPF_INC_DIR=/path/to/include $0" >&2
    exit 1
}

mkdir -p "$OUT_DIR"
# -g 出 BTF：libbpf 的 CO-RE 靠它把 struct task_struct 的字段偏移重定位到目标内核。
# 没有 -g 的 .o 在跨内核版本时表现为"加载成功但读到一堆 0"，比直接失败更难查。
"$CLANG" -O2 -g -target bpf -mcpu=v3 \
    -D__BPF_TRACING__ \
    -I "$ROOT/daemon/src" \
    -idirafter "$BPF_INC" \
    -c "$SRC" -o "$OUT"

# vmlinux.h 可选但有价值：有它才走 CO-RE 精确重定位，没它用的是源码里那份
# 最小 struct 视图，遇到厂商改过 task_struct 布局的机型会加载失败并降级。
VMLINUX_NOTE="未使用 vmlinux.h（用源码内的最小 struct 视图）"
if [[ -f "$ROOT/daemon/ebpf/vmlinux.h" ]]; then
    "$CLANG" -O2 -g -target bpf -mcpu=v3 \
        -D__BPF_TRACING__ -DENTROPY_HAVE_VMLINUX_H=1 \
        -I "$ROOT/daemon/ebpf" -I "$ROOT/daemon/src" \
        -idirafter "$BPF_INC" \
        -c "$SRC" -o "$OUT"
    VMLINUX_NOTE="已使用 daemon/ebpf/vmlinux.h（CO-RE 完整）"
fi

ls -l "$OUT"
echo "eBPF 对象: $OUT"
echo "类型解析: $VMLINUX_NOTE"
echo "下一步: scripts/build-magisk.sh 会自动把它打进模块包的 bpf/ 目录"
