#!/usr/bin/env bash
# File: scripts/build-daemon-clang.sh
#
# 不经 CMake 的构建路径，产物与 build-daemon.sh 完全一致（同 flag、同 ABI、同 API）。
#
# 为什么需要它：
#   NDK r23 之后不再自带 cmake，cmake/ninja 是 SDK 里独立的包（$SDK/cmake/<ver>），
#   只装了 platform-tools + ndk 的机器上 build-daemon.sh 会直接卡在 "找不到 cmake"。
#   而这套源码的构建结构本来就简单（4 个 target、没有配置探测），
#   用 clang 驱动直接编一条命令就能覆盖，不值得为此再让用户装一个 SDK 包。
#
# 代价：CMakeLists.txt 是这条路径的 flag 唯一来源，改 flag 要同时改两处。
# 所以下面的 COMMON/链接参数是照着 daemon/CMakeLists.txt 抄的，注释里标了对应行。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAEMON="$ROOT/daemon"
SRC="$DAEMON/src"
PROTO="$ROOT/shared/protocol"
API=24

die() { echo "错误: $*" >&2; exit 1; }

# ---------- 定位 NDK（与 build-daemon.sh 同一套规则）----------
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f "$ROOT/local.properties" ]]; then
    # sdk.dir 里是 Java properties 转义过的反斜杠（E\:\\SDK），bash 的参数展开
    # 处理不了这种双重转义，交给 python 还原成正斜杠
    SDK_DIR="$(python - "$ROOT/local.properties" <<'PY' 2>/dev/null || true
import sys
for line in open(sys.argv[1], encoding="utf-8", errors="ignore"):
    if line.startswith("sdk.dir="):
        v = line.split("=", 1)[1].strip()
        print(v.replace("\\\\", "\\").replace("\\", "/"))
        break
PY
)"
fi
[[ -n "$SDK_DIR" && -d "$SDK_DIR/ndk" ]] || die "找不到 NDK：设 ANDROID_HOME 或在 local.properties 写 sdk.dir"
NDK_VERSION="$(ls -1 "$SDK_DIR/ndk" | sort -V | tail -1)"
TC="$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/windows-x86_64"
[[ -d "$TC" ]] || TC="$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/darwin-x86_64"
[[ -d "$TC" ]] || TC="$SDK_DIR/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x64"
[[ -d "$TC" ]] || die "NDK 里没有预期的 toolchains/llvm/prebuilt/<host> 目录"
SYS="$TC/sysroot"
CC="$TC/bin/clang"
CXX="$TC/bin/clang++"
echo "使用 NDK: $SDK_DIR/ndk/$NDK_VERSION"

# ---------- flag（抄自 daemon/CMakeLists.txt 的 ENTROPY_COMMON_FLAGS）----------
COMMON=(-Wall -Wextra -Wno-unused-parameter -fvisibility=hidden
        -fno-exceptions -fno-rtti -Os -D_FORTIFY_SOURCE=2 -fstack-protector-strong
        -D__ENTROPY_DAEMON__=1
        -I"$SRC" -I"$PROTO")

build_abi() {
    local abi="$1" triple="$2"
    local out="$DAEMON/out/$abi"
    local obj="$out/objs"
    rm -rf "$out"
    mkdir -p "$obj"

    local link_atomic=""
    # armv7 的 8 字节原子量走 libatomic 调用（arm64 是原生指令，不需要）。
    # C++ 侧的 std::atomic<int64_t> 同样要它，所以四个 target 都要带。
    [[ "$abi" == "armeabi-v7a" ]] && link_atomic="-latomic"

    # C 与 C++ 必须分别用 clang / clang++ 编：clang++ 会把 .c 当 C++ 编，
    # sysfs.c 里的复合字面量与 designated initializer 在 C++ 下直接编不过。
    for f in "$SRC"/*.c; do
        "$CC" --target="$triple" --sysroot="$SYS" -std=gnu11 "${COMMON[@]}" \
            -fPIC -c "$f" -o "$obj/$(basename "$f" .c).o"
    done
    for f in "$SRC"/*.cpp; do
        "$CXX" --target="$triple" --sysroot="$SYS" -std=c++20 "${COMMON[@]}" \
            -c "$f" -o "$obj/$(basename "$f" .cpp).o"
    done

    local d="$obj"
    # entropyd：调度核心。静态 STL —— Magisk 包里不带 libc++_shared.so。
    "$CXX" --target="$triple" --sysroot="$SYS" \
        -static-libstdc++ -static-libgcc -Wl,-s -Wl,--build-id=sha1 \
        "$d/main.o" "$d/ipc_server.o" "$d/scheduler.o" "$d/safety_fuse.o" \
        "$d/limit_detector.o" "$d/ebpf_loader.o" "$d/sysfs.o" \
        -llog -lm $link_atomic -o "$out/entropyd"

    # 温控与 freezer 是独立进程：纯 C，崩溃不会把调度核心一起带走
    "$CC" --target="$triple" --sysroot="$SYS" -static-libgcc -Wl,-s -Wl,--build-id=sha1 \
        "$d/thermal_monitor.o" "$d/sysfs.o" -lm $link_atomic -o "$out/entropy-monitor"
    "$CC" --target="$triple" --sysroot="$SYS" -static-libgcc -Wl,-s -Wl,--build-id=sha1 \
        "$d/bg_freezer.o" "$d/sysfs.o" $link_atomic -o "$out/entropy-freezer"

    # 帧率探针：被注入进别人的进程，所以依赖必须最少。
    # 只链 dl（真符号靠 dlsym(RTLD_NEXT) 现取），绝不链 libEGL ——
    # 注入一个没加载 libEGL 的进程时，缺库会让目标进程连启动都失败。
    "$CC" --target="$triple" --sysroot="$SYS" -std=gnu11 \
        -shared -fPIC -Wl,-s -Wl,--gc-sections -Wl,--build-id=sha1 \
        "${COMMON[@]}" "$SRC/native_hook.c" -ldl $link_atomic \
        -o "$out/libentropy_hook.so"

    for f in entropyd entropy-monitor entropy-freezer libentropy_hook.so; do
        [[ -s "$out/$f" ]] || die "$abi 的 $f 没产出"
    done
    echo "== $abi 完成: $(du -sh "$out" | cut -f1)"
    ls -1 "$out"/entropyd "$out"/entropy-monitor "$out"/entropy-freezer "$out"/libentropy_hook.so
}

build_abi "arm64-v8a"   "aarch64-linux-android$API"
build_abi "armeabi-v7a" "armv7a-linux-androideabi$API"

cat <<EOF

产物已就位（daemon/out/<abi>/）。
  APK：./gradlew :app:assembleDebug（stageDaemon* 会自动搬进 assets/native）
  eBPF 对象这里不编：它要的是宿主 libbpf 头，见 scripts/build-ebpf.sh
EOF
