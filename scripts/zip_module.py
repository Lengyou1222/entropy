#!/usr/bin/env python3
"""File: scripts/zip_module.py

把 magisk/ 的暂存目录打成可刷入的 zip。

为什么不用 `zip` 命令行：
  Git Bash / Windows 上大概率没有 zip，而且 zip 会把宿主的换行符原样带进去。
  Magisk 的 updater-script 与几个 .sh 由 /system/bin/sh 执行，多一个 \\r 就是
  "attempt to launch /system/bin/sh failed" 这种最难查的报错。
  所以这里在写入前统一把文本条目规范成 LF，并按需给可执行位。

用法: zip_module.py <staging_dir> <out_zip>
"""
import os
import stat
import sys
import zipfile

# Magisk 侧要求这几个入口能被 sh 直接执行
EXEC_NAMES = {"update-binary", "updater-script", "customize.sh", "service.sh",
              "post-fs-data.sh", "uninstall.sh"}
EXEC_SUFFIX = (".sh",)
BIN_DIR_MARKER = os.sep + "bin" + os.sep
# 文本条目：写入前强制 LF。二进制（ELF 可执行与 .so）永远按原样写入，
# 它们的扩展名不在 TEXT_EXTS 里，也不在 TEXT_NAMES 里。
TEXT_NAMES = {"updater-script", "module.prop", "sepolicy.rule", "system.prop",
              "service.sh", "customize.sh", "uninstall.sh", "update-binary"}
TEXT_EXTS = (".sh", ".prop", ".rule", ".txt")


def is_exec(arc_path, host_path):
    name = os.path.basename(arc_path)
    if name in EXEC_NAMES:
        return True
    if name.endswith(EXEC_SUFFIX):
        return True
    # bin/ 下全是 native 产物，一律 0755
    if BIN_DIR_MARKER in host_path and os.path.isfile(host_path):
        return True
    return False


def is_text(name):
    return name in TEXT_NAMES or os.path.splitext(name)[1] in TEXT_EXTS


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    staging, out_zip = sys.argv[1], sys.argv[2]
    if not os.path.isdir(staging):
        sys.exit("暂存目录不存在: %s" % staging)

    entries = []
    for dirpath, dirnames, filenames in os.walk(staging):
        dirnames.sort()
        for fn in sorted(filenames):
            host = os.path.join(dirpath, fn)
            arc = os.path.relpath(host, staging).replace(os.sep, "/")
            entries.append((arc, host))
    # 排序后写入：zip 条目顺序稳定，同一个源目录两次构建产出可比对
    entries.sort()

    os.makedirs(os.path.dirname(os.path.abspath(out_zip)) or ".", exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for arc, host in entries:
            with open(host, "rb") as f:
                data = f.read()
            if is_text(arc.rsplit("/", 1)[-1]):
                # 只吃 CRLF，不动裸 CR（POSIX sh 对裸 CR 会报错，这里不猜用户的意图）
                data = data.replace(b"\r\n", b"\n")
                if b"\r" in data:
                    sys.exit("%s 里存在裸 CR，Magisk 端会解析失败" % arc)
                if not data.endswith(b"\n") and data:
                    data += b"\n"
            mode = 0o755 if is_exec(arc, host) else 0o644
            zi = zipfile.ZipInfo(arc, date_time=(1980, 1, 1, 0, 0, 0))
            zi.external_attr = (stat.S_IFREG | mode) << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(zi, data)
    print("写入 %d 个条目 -> %s" % (len(entries), out_zip))


if __name__ == "__main__":
    main()
