/*
 * File: daemon/src/ebpf_loader.h
 *
 * eBPF 加载器 + 负载信号采集。
 *
 * 现实约束（这段注释就是这套 API 存在两档设计的原因）：
 *  Android 上 eBPF 真正可用需要同时满足：内核 >= 4.14、开了 CONFIG_BPF_SYSCALL
 *  与 BTF（>= 5.8 才有 vmlinux BTF）、SELinux 允许 untrusted_app/system_server
 *  之外的域执行 BPF_PROG_LOAD。Magisk 以 kernel/su 域运行熵的 Daemon 时通常可以，
 *  但 ADB 模式（无 root）一定不行。
 *  所以采集层必须有一个「无 eBPF 也能拿到同一批语义」的退化路径，
 *  否则预测性调频在 90% 的普通用户机器上直接失效 —— 那不如不做。
 *
 * 两条路径输出同一个 entropy_load_metrics，调度器不关心信号来自哪里。
 */
#ifndef ENTROPY_EBPF_LOADER_H
#define ENTROPY_EBPF_LOADER_H

#include "sysfs.h"

#include <stdint.h>
#include <stdbool.h>

typedef enum {
    ENT_SIGNAL_NONE = 0,
    ENT_SIGNAL_EBPF,          /* tracepoint + ring buffer，微秒级 */
    ENT_SIGNAL_SCHEDSTAT,     /* 轮询 /proc/[pid]/task/[tid]/schedstat，毫秒级 */
} entropy_signal_source;

/* 调度器真正需要的东西，只有这四个量 */
typedef struct {
    int64_t  window_ms;             /* 本组指标的统计窗口 */
    uint64_t voluntary_switch;      /* 正常让出 */
    uint64_t preempt_count;         /* 被抢占次数 —— 掉帧的头号嫌疑 */
    int64_t  wakeup_lat_p95_ns;     /* 唤醒延迟 95 分位，>2ms 就该提优先级/绑核 */
    int64_t  gpu_last_submit_ns;    /* 距上次 GPU 提交的间隔；>vsync 周期即认为管线空转 */
    int32_t  render_thread_busy;    /* 是否有活跃渲染线程 0/1 */
    int32_t  target_tgid;
    int32_t  target_threads;        /* 目标进程内被统计的线程数 */
} entropy_load_metrics;

int  ent_ebpf_init(bool allow_fallback);
void ent_ebpf_shutdown(void);

/*
 * 注入硬件映射（由 main.cpp 在 hwmap 扫描完成后调用一次）。
 * 退化采样路径需要知道 GPU busy 节点在哪；用注入而不是全局符号，
 * 是为了让这个模块可以被单独编译进测试二进制。
 */
void ent_ebpf_bind_hwmap(const entropy_hwmap *hw);

/* 把前台游戏 tgid 注册为观察对象。传 0 取消。 */
int  ent_ebpf_watch(int32_t tgid);

/* 拉取窗口内聚合指标；实现保证不阻塞超过 ~1ms */
int  ent_ebpf_sample(int64_t window_ms, entropy_load_metrics *out);

entropy_signal_source ent_ebpf_source(void);
const char *ent_ebpf_source_name(void);

/* 从内嵌 section 里 dump 出 .bpf.o 到 tmpfs（BPF_PROG_LOAD 需要文件或内存镜像，
 * 内存镜像通过 syscall 的 prog_data 直接给，这里保留 dump 仅用于调试与离线验证）。 */
int ent_ebpf_dump_object(const char *path);

#endif /* ENTROPY_EBPF_LOADER_H */
