#!/usr/bin/env bash
# File: scripts/build-apk.sh
#
# 一条命令出 APK：同步到纯 ASCII 影子目录 -> 编 native -> gradle 打包 -> 回填 dist/。
#
# 为什么要影子目录：
#   AGP 在 Windows 上直接拒绝非 ASCII 工程路径（"Your project path contains
#   non-ASCII characters"），这不是 warning 而是 apply plugin 阶段就失败；
#   加 android.overridePathCheck=true 只是把检查关掉，NDK 与 aapt2 在 GBK 代码页下
#   仍然会把路径读成乱码。改用户自己的目录结构来迁就工具链是不合理的，
#   所以构建时复制一份到 ASCII 路径，源码目录保持原样。
#
# 复制的是源码，不是产物：build/、.gradle/ 留在影子目录里，原工程始终干净。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHADOW="${ENTROPY_BUILD_DIR:-$HOME/entropy-build/1.0.0}"
ABIS=("arm64-v8a" "armeabi-v7a")
ARTIFACTS=(entropyd entropy-monitor entropy-freezer libentropy_hook.so)

die() { echo "错误: $*" >&2; exit 1; }

# 影子目录必须是纯 ASCII，否则这一趟复制毫无意义
case "$SHADOW" in
    *[![:ascii:]]*) die "影子目录含非 ASCII 字符: $SHADOW（用 ENTROPY_BUILD_DIR 指定一个纯英文路径）" ;;
esac
[[ "$SHADOW" != "$ROOT" ]] || die "影子目录与工程目录相同，复制会自覆盖"

VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)"
[[ -n "$VERSION" ]] || die "读不到 app/build.gradle.kts 里的 versionName"

echo "== 同步 $ROOT -> $SHADOW"
mkdir -p "$SHADOW"
# app 的 src 由下面的整目录复制带来；--delete 语义靠先删后拷保证不留残file
for d in app/src app/build.gradle.kts app/proguard-rules.pro daemon/src daemon/ebpf daemon/CMakeLists.txt \
         shared magisk scripts gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts \
         gradle.properties local.properties keystore.properties entropy.keystore; do
    [[ -e "$ROOT/$d" ]] || continue
    src="$ROOT/$d"
    dst="$SHADOW/$d"
    mkdir -p "$(dirname "$dst")"
    rm -rf "$dst"
    cp -R "$src" "$dst"
done
# 工具链版本由脚本钉死，不跟随原工程里的 wrapper：
# AGP 8.7.3 只支持 Gradle 8.9~8.x，一旦落到 Gradle 9 会在配置阶段就炸，
# 而 IDE 有把自己的发行版写回 gradle-wrapper.properties 的习惯。
GRADLE_PIN="8.13"
mkdir -p "$SHADOW/gradle/wrapper"
printf '%s\n' \
    'distributionBase=GRADLE_USER_HOME' \
    'distributionPath=wrapper/dists' \
    "distributionUrl=https\\://services.gradle.org/distributions/gradle-$GRADLE_PIN-bin.zip" \
    'networkTimeout=10000' \
    'validateDistributionUrl=true' \
    'zipStoreBase=GRADLE_USER_HOME' \
    'zipStorePath=wrapper/dists' > "$SHADOW/gradle/wrapper/gradle-wrapper.properties"
if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.properties" ]] &&
   ! grep -q "gradle-$GRADLE_PIN-bin.zip" "$ROOT/gradle/wrapper/gradle-wrapper.properties"; then
    echo "提示: 原工程的 wrapper 不是 Gradle $GRADLE_PIN，本次构建按影子目录里的 $GRADLE_PIN 走"
fi

# stageDaemon 任务从 daemon/out/<abi> 搬运，影子目录里没有 out 就必须先编 native
for abi in "${ABIS[@]}"; do
    for f in "${ARTIFACTS[@]}"; do
        if [[ ! -s "$SHADOW/daemon/out/$abi/$f" ]]; then
            echo "== daemon/out/$abi 缺 $f，先跑 build-daemon-clang.sh"
            (cd "$SHADOW" && bash scripts/build-daemon-clang.sh)
            break 2
        fi
    done
done

echo "== gradle 打包"
# --no-daemon：lint 会把 aar 里的 jar 拷到 intermediates 并内存映射，daemon 活着的时候
# 这些文件在 Windows 上是删不掉的，而本脚本每次都要 rm -rf 重新同步，会直接卡死。
# 额外参数原样转给 gradle，用来在不改源码的前提下换构建期配置，例如：
#   bash scripts/build-apk.sh -Pentropy.updateRepo=owner/repo   # 把更新通道指到别的仓库实测
(cd "$SHADOW" && sh ./gradlew :app:assembleRelease :app:assembleDebug --no-daemon --console=plain "$@")

mkdir -p "$ROOT/dist"
# native 产物一并回填：build-magisk.sh 和"手动 adb push 调试"都从工程目录里取，
# 产物只躺在影子目录等于没产出。
for abi in "${ABIS[@]}"; do
    if [[ -d "$SHADOW/daemon/out/$abi" ]]; then
        mkdir -p "$ROOT/daemon/out/$abi"
        for f in "${ARTIFACTS[@]}"; do
            cp -f "$SHADOW/daemon/out/$abi/$f" "$ROOT/daemon/out/$abi/$f"
        done
    fi
done
for pair in "release:entropy-v$VERSION.apk" "debug:entropy-v$VERSION-debug.apk"; do
    bt="${pair%%:*}"; name="${pair##*:}"
    apk="$SHADOW/app/build/outputs/apk/$bt/app-$bt.apk"
    [[ -s "$apk" ]] || die "$bt 没产出 APK：$apk"
    cp -f "$apk" "$ROOT/dist/$name"
done

# 出包后立刻自证：签名是不是预期的那个。
# build-tools 版本号不能写死（机器上装的是 34/35/36/37 都有可能），取最新一个。
# local.properties 里的 sdk.dir 有两种写法都要认：Java properties 转义的 E\:\\SDK
# 和手写的 E:\SDK。bash 的参数展开对反斜杠的处理太隐晦，这一步交给 python 做。
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f "$ROOT/local.properties" ]]; then
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
APKSIGNER="$(ls -1d "${SDK_DIR%/}"/build-tools/*/apksigner.bat "${SDK_DIR%/}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [[ -z "$APKSIGNER" ]]; then
    echo "提示: 没找到 apksigner（ANDROID_HOME=$SDK_DIR），跳过出包后的签名自证"
elif [[ ! -f "$ROOT/keystore.properties" ]]; then
    echo "提示: 没有 keystore.properties，release 走的是 debug 占位签名"
else
    echo "== 签名自证"
    "$APKSIGNER" verify --print-certs "$ROOT/dist/entropy-v$VERSION.apk" 2>&1 | sed -n '1,2p'
fi

cat <<EOF

完成：
  $ROOT/dist/entropy-v$VERSION.apk          （release，R8 压缩）
  $ROOT/dist/entropy-v$VERSION-debug.apk    （debug，带调试信息）

装机：adb install -r "dist/entropy-v$VERSION.apk"
EOF
