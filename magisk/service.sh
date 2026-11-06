#!/system/bin/sh
# File: magisk/service.sh
#
# 开机后拉常驻服务。这一版是"带看门狗的启动"，但刻意不做无限快速重启：
# 一个每次都以 SIGSEGV 收场的二进制，重启循环会把设备变成暖手宝。
# 连续失败三次就停手，并把原因写进日志等用户来看。

MODDIR=${0%/*}
BIN=$MODDIR/bin/current
LOG=/data/local/tmp/entropy.log
TLDIR=/data/local/tmp

# /data/local/tmp 是 App 与 adb 都认的路径：App 的 HardwareBridge 只会去这里找
# entropyd。模块装好之后必须让文件出现在同一个位置，否则"刷了模块但 App 说
# 没检测到 Daemon"，用户会以为模块没生效。
for f in entropyd entropy-monitor entropy-freezer libentropy_hook.so; do
    cp -f "$BIN/$f" "$TLDIR/$f" 2>/dev/null
    chmod 755 "$TLDIR/$f" 2>/dev/null
    restorecon "$TLDIR/$f" 2>/dev/null
done

# eBPF 对象是可选增强：build-ebpf.sh 没跑过就没有这个目录，
# 这时 entropyd 会走 schedstat 采样路径。不要在这里留 "No such file" 的噪音。
BPF_OBJ="$MODDIR/bpf/entropy_sched.bpf.o"
if [ -f "$BPF_OBJ" ]; then
    cp -f "$BPF_OBJ" "$TLDIR/entropy_sched.bpf.o" 2>/dev/null
    chmod 644 "$TLDIR/entropy_sched.bpf.o" 2>/dev/null
    restorecon "$TLDIR/entropy_sched.bpf.o" 2>/dev/null
fi

# 模块刷过的设备不需要 entropy.allow：只有 root/shell/system 三个 uid 能连，
# App 自己在授权步骤里写自己的 uid（见 HardwareBridge.grantSelfUid）。
# 这里不预先创建该文件，避免"安装即授权"。

start_once() {
    "$TLDIR/entropyd" --profile=balanced >> "$LOG" 2>&1 &
    echo $! > "$TLDIR/entropy.pid"
}

fails=0
while [ "$fails" -lt 3 ]; do
    # 已经在跑就不再重复起：熵的 App 也会在用户点"启用"时拉起同一个进程，
    # 两个 entropyd 会互相覆盖 sysfs 备份表，那是最坏的竞态。
    if [ -z "$(pidof entropyd 2>/dev/null)" ]; then
        start_once
    fi
    i=0
    while [ "$i" -lt 60 ]; do
        sleep 10
        i=$((i + 1))
        [ -z "$(pidof entropyd 2>/dev/null)" ] && break
    done
    # 活满 10 分钟说明不是启动期崩溃，失败计数归零
    if [ "$i" -ge 60 ]; then
        fails=0
    else
        fails=$((fails + 1))
        echo "$(date) service.sh: entropyd 退出，第 $fails 次重启" >> "$LOG"
        sleep 5
    fi
done
echo "$(date) service.sh: 连续失败 3 次，停止重启。请查看 $LOG" >> "$LOG"
