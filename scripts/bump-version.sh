#!/usr/bin/env bash
# File: scripts/bump-version.sh
#
# 版本号推进。口径是用户定的那三条：
#   小更新 +0.01   1.0.0 -> 1.0.1，1.0.9 -> 1.1.0（第三段满 10 往前进位）
#   中更新 +0.1    1.0.7 -> 1.1.0
#   大更新 +1.0    1.4.2 -> 2.0.0
#
# versionCode 不手写，由三段算出来：major*10000 + minor*100 + patch。
# 这样它天然单调递增，Android 才肯把新包当"更新"覆盖安装；
# 手写 versionCode 迟早会出现版本名涨了、版本码忘了涨的包。
#
# 用法：bash scripts/bump-version.sh minor|medium|major
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
KIND="${1:-}"

die() { echo "错误: $*" >&2; exit 1; }

case "$KIND" in
    minor|medium|major) ;;
    *) die "用法: $0 minor|medium|major   （小更新 +0.01 / 中更新 +0.1 / 大更新 +1.0）" ;;
esac
[[ -f "$GRADLE" ]] || die "找不到 $GRADLE"

NAME="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
[[ "$NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] ||
    die "versionName 不是 a.b.c 三段式，读到的值是 '$NAME'，先手工改回三段式再 bump"

MAJOR="${BASH_REMATCH[1]}"; MINOR="${BASH_REMATCH[2]}"; PATCH="${BASH_REMATCH[3]}"

case "$KIND" in
    minor)
        PATCH=$((PATCH + 1))
        if ((PATCH >= 10)); then PATCH=0; MINOR=$((MINOR + 1)); fi
        ;;
    medium)
        MINOR=$((MINOR + 1)); PATCH=0
        if ((MINOR >= 10)); then MINOR=0; MAJOR=$((MAJOR + 1)); fi
        ;;
    major)
        MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0
        ;;
esac

NEW="$MAJOR.$MINOR.$PATCH"
NEW_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

# 只改这两行，别的行一个字节都不动 —— 用 sed -i 整文件重写会把行尾与缩进一起改掉，
# 一次 bump 就产出满屏 diff
TMP="$(mktemp)"
awk -v n="$NEW" -v c="$NEW_CODE" '
    /^[[:space:]]*versionCode = [0-9]+/ { sub(/[0-9]+$/, c); print; next }
    /^[[:space:]]*versionName = "/ { sub(/"[^"]*"/, "\"" n "\""); print; next }
    { print }
' "$GRADLE" > "$TMP"
grep -q "versionName = \"$NEW\"" "$TMP" || die "写入失败：awk 没匹配到 versionName 那一行"
grep -q "versionCode = $NEW_CODE" "$TMP" || die "写入失败：awk 没匹配到 versionCode 那一行"
mv "$TMP" "$GRADLE"

echo "$KIND: $NAME -> $NEW   (versionCode $NEW_CODE)"
echo "下一步：bash scripts/build-apk.sh，产物名会是 dist/entropy-v$NEW.apk"
echo "发版：  bash scripts/publish-github.sh v$NEW"
