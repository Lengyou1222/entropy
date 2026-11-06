#!/system/bin/sh
# File: magisk/uninstall.sh
#
# 卸载必须先把进程停掉再删文件：entropyd 是 root 进程，它握着 sysfs 的备份表。
# 文件被删了但进程还在的话，熔断线程读不到状态文件却仍然在写频率，
# 用户重启前会拿到一个"没人还原"的降频状态。顺序是刻意反过来的：
#   停探针注入 -> 还原硬件 -> 停守护 -> 停监控 -> 删文件。

TL=/data/local/tmp

# 1. 摘掉 LD_PRELOAD 帧率探针：属性没了，下次进程启动就不会再被注入。
# 遍历 pm list packages 会有几百次 getprop，改成只扫属性表里真实存在的 wrap.* 键。
resetprop 2>/dev/null | sed -n 's/^\[\(wrap\.[^]]*\)\].*/\1/p' | while read -r key; do
    case "$(getprop "$key" 2>/dev/null)" in
        *libentropy_hook.so*) resetprop -d "$key" >/dev/null 2>&1 ;;
    esac
done

# 2. 让守护进程自己还原硬件（它有完整备份表；直接 kill 就没人还原了）
"$TL/entropyd" --reset >/dev/null 2>&1
sleep 1

# 3. 仍在跑的进程再收掉
for n in entropyd entropy-monitor entropy-freezer; do
    p="$(pidof $n 2>/dev/null)"
    [ -n "$p" ] && kill "$p" 2>/dev/null
done
sleep 1
for n in entropyd entropy-monitor entropy-freezer; do
    p="$(pidof $n 2>/dev/null)"
    [ -n "$p" ] && kill -9 "$p" 2>/dev/null
done

rm -f "$TL/entropyd" "$TL/entropy-monitor" "$TL/entropy-freezer" "$TL/libentropy_hook.so"
rm -f "$TL/entropy.sock" "$TL/entropy.pid" "$TL/entropy.state" "$TL/entropy.allow"
# .o 只是个被 open 过的文件，删掉它不会撤销已加载的程序 —— 那要靠上面的 kill。
rm -f "$TL/entropy_sched.bpf.o"
# 日志留着：卸载之后如果还有发热/降频问题，这份日志是唯一能定位的证据
exit 0
