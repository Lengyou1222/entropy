#!/usr/bin/env bash
# File: scripts/publish-github.sh
#
# 把工程推到 GitHub、发一个带 APK 资产的 Release、开启 Pages。
#
# 这个脚本是给你（仓库所有者）跑的，不是给我跑的：推送到远端、建 Release、
# 改 Pages 设置都是对外可见、且撤销代价高的动作，凭据也不该经过我。
# 所以脚本只做两件事——把该做的步骤按顺序摆出来，以及在缺凭据时把"你自己在
# 网页上点哪几步"打印清楚，而不是想办法绕过授权。
#
# 用法：
#   bash scripts/publish-github.sh                 # 用 build.gradle.kts 里的版本号
#   bash scripts/publish-github.sh --dry-run       # 只打印要做什么，不动远端
#   GH_TOKEN=... bash scripts/publish-github.sh    # 有 token 才建 Release 与开 Pages
#
# 远端默认 https://github.com/Lengyou1222/entropy.git；换仓库用
#   ENTROPY_REMOTE=git@github.com:you/entropy.git bash scripts/publish-github.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OWNER_REPO="${ENTROPY_REPO:-Lengyou1222/entropy}"
REMOTE_URL="${ENTROPY_REMOTE:-https://github.com/${OWNER_REPO}.git}"
BRANCH="${ENTROPY_BRANCH:-main}"
PAGES_PATH="/docs"
DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

die() { echo "错误: $*" >&2; exit 1; }
run() {
    if [[ $DRY == 1 ]]; then echo "  [dry-run] $*"; else echo "  \$ $*"; "$@"; fi
}

cd "$ROOT"

VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[[ -n "$VERSION" ]] || die "读不到 app/build.gradle.kts 里的 versionName"
APK="dist/entropy-v${VERSION}.apk"
TAG="v${VERSION}"

echo "== 发布 $TAG 到 $OWNER_REPO"

# ------------------------------------------------------------------ #
# 1. 先确认签名材料不会被推上去                                            #
#                                                                    #
#   私库和密码一旦进了 git 历史，删掉提交也拿不回来——远端仓库是公开的，       #
#   这一步只能是硬失败，不能是警告。所以放在 add 之后、commit 之前查索引。     #
# ------------------------------------------------------------------ #
if [[ $DRY == 0 && ! -d .git ]]; then
    echo "-- 还没有 git 仓库，初始化"
    git init -b "$BRANCH"
fi

if [[ $DRY == 0 ]]; then
    git add -A
    leak="$(git diff --cached --name-only | grep -E 'keystore|\.jks$' || true)"
    [[ -z "$leak" ]] || die "这些签名材料进了暂存区：$leak（.gitignore 没盖住，先修它）"

    if ! git diff --cached --quiet; then
        run git commit -m "${COMMIT_MSG:-熵 ${VERSION}}"
    else
        echo "-- 工作区没有待提交的改动"
    fi
else
    echo "  [dry-run] git add -A && git commit（提交前会检查暂存区里没有 keystore）"
fi

# ------------------------------------------------------------------ #
# 2. 推送                                                              #
#                                                                    #
#   登录这一步天生是浏览器里的，所以凭据只经过你的手，不经过这个脚本。         #
# ------------------------------------------------------------------ #
echo "-- 推送到 $REMOTE_URL（第一次会弹浏览器让你登录 GitHub）"
if [[ $DRY == 0 ]]; then
    git remote get-url origin >/dev/null 2>&1 || run git remote add origin "$REMOTE_URL"
    cur="$(git remote get-url origin)"
    [[ "$cur" == "$REMOTE_URL" ]] || { echo "  origin 已指向 $cur，沿用它"; }
    run git push -u origin "$BRANCH"
else
    echo "  [dry-run] git push -u origin $BRANCH"
fi

# ------------------------------------------------------------------ #
# 3. Release：建 tag + 传 APK                                           #
#                                                                    #
#   有 gh 或 GH_TOKEN 就自动做；都没有就把网页上的三步打印出来。             #
#   不做"用账密登录"这种事——那既要你的凭据经过脚本，也不是能自动化的正路。     #
# ------------------------------------------------------------------ #
if [[ ! -f "$APK" ]]; then
    echo "!! $APK 不存在。先 bash scripts/build-apk.sh 再发 Release。"
    APK=""
fi

RELEASE_DONE=0
PYBIN="$(command -v python || command -v python3 || true)"

if [[ -n "$APK" ]]; then
    NOTES="${RELEASE_NOTES:-熵 ${VERSION}

小更新 +0.01 / 中等更新 +0.1 / 大更新 +1.0，versionCode = a*10000 + b*100 + c。
应用内每次冷启动查一次本 Release，发现新版本可选下载或继续使用当前版本。}"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        echo "-- 用 gh 创建 Release"
        run gh release create "$TAG" "$APK" \
            --repo "$OWNER_REPO" --title "熵 ${VERSION}" --notes "$NOTES" && RELEASE_DONE=1
    elif [[ -n "${GH_TOKEN:-}" && -n "$PYBIN" ]]; then
        # REST 这条路要自己拼 JSON：说明里有中文与换行，转义交给 python，不手搓
        echo "-- 用 GH_TOKEN 走 REST 创建 Release"
        BODY="$("$PYBIN" -c 'import json,sys;print(json.dumps({"tag_name":sys.argv[1],"name":sys.argv[2],"body":sys.argv[3],"draft":False,"prerelease":False}))' \
              "$TAG" "熵 ${VERSION}" "$NOTES")"
        UPLOAD="$(curl -fsSL -X POST -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
            "https://api.github.com/repos/$OWNER_REPO/releases" -d "$BODY" \
            | "$PYBIN" -c 'import json,sys;print(json.load(sys.stdin)["upload_url"].split("{")[0])')"
        [[ -n "$UPLOAD" ]] || die "建 Release 没拿到 upload_url，检查 token 有没有 repo 权限"
        run curl -fsSL -X POST -H "Authorization: Bearer $GH_TOKEN" \
            -H "Content-Type: application/octet-stream" \
            "$UPLOAD?name=$(basename "$APK")" --data-binary "@$APK"
        RELEASE_DONE=1
        echo "-- Release $TAG 已发布，资产 $(basename "$APK")"
    fi
    if [[ $RELEASE_DONE == 0 ]]; then
        cat <<EOF

-- 没有可用的 gh 登录态（也没有 GH_TOKEN+python），Release 这一步请你在网页上做（三下）：
   1) 打开 https://github.com/$OWNER_REPO/releases/new
   2) Choose a tag 里新建 "$TAG"，标题写「熵 ${VERSION}」，正文粘说明
   3) 把 ${APK:-（先跑 build-apk.sh）} 拖进 "Attach binaries" 后点 Publish release
   做完后应用内的检查更新就会读到它（公开仓库匿名可读，应用里不需要任何凭据）。
EOF
    fi
fi

# ------------------------------------------------------------------ #
# 4. Pages：source 指到 /docs                                          #
# ------------------------------------------------------------------ #
PAGES_URL="https://${OWNER_REPO%%/*}.github.io/${OWNER_REPO##*/}/"
if [[ -n "${GH_TOKEN:-}" ]] && command -v curl >/dev/null 2>&1 && [[ $DRY == 0 ]]; then
    if curl -fsSL -X POST -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$OWNER_REPO/pages" \
        -d "{\"source\":{\"branch\":\"$BRANCH\",\"path\":\"$PAGES_PATH\"}}" >/dev/null 2>&1; then
        echo "-- Pages 已开启：$PAGES_URL"
    else
        echo "-- Pages 可能已经开着（重复开启会返回 409）。访问 $PAGES_URL 确认"
    fi
else
    cat <<EOF

-- Pages 这一步（没有 GH_TOKEN 时同样请你在网页上做）：
   Settings -> Pages -> Build and deployment
     Source: Deploy from a branch
     Branch: $BRANCH / 目录: $PAGES_PATH
   保存后主页在 $PAGES_URL
EOF
fi

cat <<EOF

== 收尾自检
   [ ] https://github.com/$OWNER_REPO 的 $BRANCH 分支能看到源码，且没有 keystore.properties / entropy.keystore
   [ ] Releases 最新一条挂着 entropy-v${VERSION}.apk（应用内下载读的就是这个资产）
   [ ] $PAGES_URL 打得开，下载按钮指向刚发的那个 Release
EOF
