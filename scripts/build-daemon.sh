#!/usr/bin/env bash
# File: scripts/build-daemon.sh
#
# 用 NDK 交叉编译 entropyd / entropy-monitor / entropy-freezer / libentropy_hook.so，
# 产物落在 daemon/out/<abi>/，由 app 模块的 stageDaemon<abi> 任务搬进 APK assets。
#
# 为什么要单独一个脚本而不是塞进 Gradle：
#  Daemon 要能被 Magisk 模块和 ADB 手动推送这两条路复用。如果只有 Gradle 能编它，
#  "只刷模块不装 App" 的用法就得连 Android Studio 一起装，这不合理。
#
# 依赖：cmake >= 3.22、ninja、Android NDK r26+。
#  SDK 里的 cmake 目录（$ANDROID_HOME/cmake/<ver>/bin）存在就自动用，否则用 PATH 上的。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAEMON="$ROOT/daemon"
ABI_LIST=("arm64-v8a" "armeabi-v7a")
API=24                     # 与 app 的 minSdk 保持一致：低 API 的 bionic 没有 gettid() 等符号

die() { echo "错误: $*" >&2; exit 1; }

# ---------- 定位 NDK ----------
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
NDK="$SDK_DIR/ndk/$NDK_VERSION"
[[ -d "$NDK" ]] || die "NDK $NDK_VERSION 目录不存在"
echo "使用 NDK: $NDK"

# ---------- 定位 cmake / ninja ----------
CMAKE_BIN="$(command -v cmake || true)"
if [[ -z "$CMAKE_BIN" ]]; then
    CMAKE_DIR="$(ls -1d "$SDK_DIR"/cmake/*/ 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$CMAKE_DIR" ]] || die "既不在 PATH 上也未在 $SDK_DIR/cmake 下找到 cmake（需要 >= 3.22）"
    export PATH="${CMAKE_DIR%/}/bin:$PATH"
    CMAKE_BIN="$CMAKE_DIR/bin/cmake"
fi

# ---------- 逐 ABI 配置 + 编译 ----------
for abi in "${ABI_LIST[@]}"; do
    case "$abi" in
        arm64-v8a)   arch="aarch64"; triplet="aarch64-linux-android" ;;
        armeabi-v7a) arch="arm";      triplet="arm-linux-androideabi" ;;
        *) die "未知 ABI: $abi" ;;
    esac
    build="$DAEMON/out/$abi"
    # 干净配置：CMake 的缓存会把上一次的 ABI 带进来，串 ABI 是这类脚本最常见的坑
    rm -rf "$build/CMakeCache.txt" "$build/CMakeFiles" 2>/dev/null || true
    "$CMAKE_BIN" -S "$DAEMON" -B "$build" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$(command -v ninja || echo "$SDK_DIR/cmake"/*/bin/ninja)" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$API" \
        -DANDROID_STL="c++_static" \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DCMAKE_BUILD_WITH_INSTALL_RPATH=OFF
    "$CMAKE_BIN" --build "$build" --parallel
    echo "== $abi 完成:"
    ls -1 "$build"/entropyd "$build"/entropy-monitor "$build"/entropy-freezer \
           "$build"/libentropy_hook.so 2>/dev/null || die "$abi 产物不完整（libentropy_hook.so 需要纯 C 编译通过）"
done

cat <<'EOF'

产物已就位。接下来三选一：
  A) 打进 APK：直接 ./gradlew :app:assembleRelease（stageDaemon* 任务会自动搬运）
  B) 手动推送：adb push daemon/out/arm64-v8a/{entropyd,entropy-monitor,entropy-freezer,libentropy_hook.so} /data/local/tmp/
  C) 出 Magisk 模块包：./scripts/build-magisk.sh

（可选）eBPF 探针对象不在这里编，需要 Linux 宿主 + libbpf-dev：./scripts/build-ebpf.sh
缺它不影响使用，entropyd 会自动走 schedstat 采样路径。
EOF
