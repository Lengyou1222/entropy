#!/system/bin/sh
# File: magisk/customize.sh
#
# 安装期检查。这里只做"装下去会不会没用"的判断，不做任何硬件写入 ——
# 写 sysfs 是运行时的事，装机阶段写一次就可能把用户留在一个降了频的系统里。
#
# Magisk 在调用本脚本前已经解压好整个包，MODPATH 指向模块目录。

ABI="$(grep -m1 '^abi:' /proc/self/maps 2>/dev/null | sed 's/.*abi:\([^ ]*\).*/\1/')"
[ -z "$ABI" ] && ABI="$(getprop ro.product.cpu.abi)"

case "$ABI" in
    arm64*) BIN="$MODPATH/bin/arm64-v8a" ;;
    armeabi*) BIN="$MODPATH/bin/armeabi-v7a" ;;
    *)
        ui_print "! 不支持的架构: $ABI"
        ui_print "! 本项目只提供 arm 产物：x86 机型上没有对应的调度节点，"
        ui_print "! 硬编出来的频率表会让调度器读到一堆空路径。"
        abort
        ;;
esac

for f in entropyd entropy-monitor entropy-freezer libentropy_hook.so; do
    [ -f "$BIN/$f" ] || { ui_print "! 缺少 $f，安装包不完整"; abort; }
    # busybox 在部分 ROM 上没有 stat -c，用 wc -c 这种一定能用的方式做非空校验
    [ "$(wc -c < "$BIN/$f")" -gt 64 ] || { ui_print "! $f 是空文件"; abort; }
done
# 只留本机架构那一份：模块目录会在 /data 里躺很久，
# 多带一个 ABI 就是白占一份体积。注意顺序——先搬运再删，反过来会把正在用的目录删掉。
mv "$BIN" "$MODPATH/bin/current"
rm -rf "$MODPATH/bin/arm64-v8a" "$MODPATH/bin/armeabi-v7a" 2>/dev/null

KERNEL="$(uname -r 2>/dev/null)"
case "$KERNEL" in
    5.4*|5.10*|5.15*|6.1*|6.6*|6.12*) ;;
    *) ui_print "- 内核 $KERNEL 未验证过 eBPF 路径，调度器会自动降级到采样模式，功能不中断" ;;
esac

ui_print "- 熵 v1.0.0：native 组件就位"
ui_print "- 下一步：安装同名 APK，在首屏完成授权后由 App 拉起守护进程"
