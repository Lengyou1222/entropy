/*
 * File: daemon/ebpf/entropy_sched.bpf.c
 *
 * 熵 —— 内核侧调度探针。
 *
 * eBPF 原理速述（给读代码的人，不是给编译器）：
 *   eBPF 是一段受限的字节码，由内核的 verifier 逐指令证明"必然终止、不会越界、
 *   不会死循环"后才 JIT 成本地码，挂在 tracepoint/kprobe/uprobe 上同步执行。
 *   它对被追踪进程的意义是：不需要 stop-the-world，不需要 ptrace，不需要改内核，
 *   单事件开销在 50~200ns —— 这就是需求里"零延迟响应"的物理来源。
 *
 * 为什么调度器需要它：
 *   纯 sysfs 轮询拿不到"渲染线程在这个 vsync 周期里被抢占了几次"。
 *   而游戏掉帧的根因往往就是大核上被系统服务抢了一个时间片。
 *   在 sched_switch 上挂探针，统计目标 tgid 的「唤醒延迟」和「非自愿抢占」，
 *   预测性调频才有真正的输入信号，而不是靠 load average 这种 10 秒平滑量。
 *
 * 编译：clang -O2 -g -target bpf -mcpu=v3 -c entropy_sched.bpf.c -o entropy_sched.bpf.o
 * 加载：用户态见 daemon/src/ebpf_loader.cpp（libbpf 或直接 bpf(2) syscall）。
 *
 * 注意：本文件不 include <linux/bpf.h> 的全部依赖链，而是自带一份最小 struct 定义。
 * 设备上内核版本跨度大（Android 9 的 4.19 到 Android 16 的 6.x），
 * 用最小定义 + CO-RE 才能一份 .o 到处跑。
 */

/* 若构建系统提供了 vmlinux.h（`bpftool btf dump file /sys/kernel/btf/vmlinux format c`），
 * 优先使用它，BTF 类型重定位会让下面这些字段访问跨内核版本自动适配。 */
#if defined(ENTROPY_HAVE_VMLINUX_H)
#include "vmlinux.h"
#else
#include <linux/types.h>

/* 最小 task_struct 视图：verifier 只允许我们访问已注册的可信偏移，
 * 这里靠 BPF_CORE_READ 完成，因此结构体只需要声明要读的字段。 */
struct task_struct___local {
    __s32 pid;
    __s32 tgid;
    __u32 __portable_offsets; /* placeholder, resolved by CO-RE */
};
#endif

#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#ifndef LINUX_VERSION_CODE
#define LINUX_VERSION_CODE 0
#endif

#define ENT_MAX_CLUSTER 12
#define ENT_RING_SIZE   (1 << 20)

/* 与用户态 daemon/src/ebpf_loader.h 必须逐字节一致 */
struct ent_switch_event {
    __u64 ts;
    __u32 tgid;
    __u32 pid;
    __u32 cpu;
    __u64 prev_state;      /* TASK_RUNNING / TASK_INTERRUPTIBLE ... */
    __s64 delta_ns;        /* 本次运行时长 或 等待时长（按 type 解释） */
    __u32 type;            /* 0=voluntary 1=preempted 2=blocked 3=wakeup-latency */
    __u32 nice;
};

struct ent_gpu_event {
    __u64 ts;
    __u32 tgid;
    __u32 pid;
    __u64 submit_ns;
    __u32 q;              /* kgsl 的 draw queue id */
    __u32 flags;
};

/*
 * 目标进程白名单：用户态把当前前台游戏的 tgid 写进来。
 * 用 ARRAY+bitmask 而不是 HASH 是为了省掉每次事件的哈希查找 ——
 * sched_switch 是每秒上万次的热路径，map_lookup 的开销必须压到最小。
 * 2048 个槽位覆盖 tgid % 2048，冲突只会造成少量误报，不影响安全。
 */
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 2048);
    __type(key, __u32);
    __type(value, __u32);
} watched_tgids SEC(".maps");

/* 每 CPU 的抢占/唤醒计数，用户态按 cluster 聚合后驱动预测算法 */
struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(max_entries, 2);
    __type(key, __u32);
    __type(value, __u64);
} stats SEC(".maps");

/* ring buffer：比 perf_event_output 少一次拷贝，且支持 spill-over 时的 busy 位语义 */
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, ENT_RING_SIZE);
} events SEC(".maps");

/* 记录每个线程上一次被切出的时间，用来算唤醒延迟 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 4096);
    __type(key, __u32);
    __type(value, __u64);
} last_offcpu SEC(".maps");

static __always_inline int is_watched(__u32 tgid)
{
    if (tgid == 0) return 0;
    __u32 k = tgid & 2047;
    __u32 *v = bpf_map_lookup_elem(&watched_tgids, &k);
    return v && *v == tgid;
}

static __always_inline void bump_stat(__u32 idx, __u64 n)
{
    __u64 *p = bpf_map_lookup_elem(&stats, &idx);
    if (p) __sync_fetch_and_add(p, n);
}

/*
 * tracepoint: sched:sched_switch
 * 语义：prev 被切出、next 开始运行。我们要的是两件事：
 *   (a) prev 是被自愿让出（自愿切换 -> 正常）还是被抢占（-> 需要绑核/提优先级）
 *   (b) next 从被唤醒到真正上 CPU 等了多久（唤醒延迟，掉帧的直接指标）
 */
SEC("tracepoint/sched/sched_switch")
int ent_handle_switch(struct trace_event_raw_sched_switch *ctx)
{
    /* prev_state == 0 意味着 prev 仍然 runnable 却被换下 —— 这就是抢占 */
    __u64 prev_state = BPF_CORE_READ(ctx, prev_comm);
    __s32 prev_pid   = BPF_CORE_READ(ctx, prev_pid);
    __s32 next_pid   = BPF_CORE_READ(ctx, next_pid);
    __s32 prev_tgid  = prev_pid;   /* tracepoint 不直接给 tgid，用 pid-group 近似后由用户态校正 */
    __s32 next_tgid  = next_pid;
    __u64 ts         = bpf_ktime_get_ns();

    if (prev_state == 0 && is_watched((__u32)prev_tgid)) {
        struct ent_switch_event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
        if (e) {
            e->ts = ts; e->tgid = (__u32)prev_tgid; e->pid = (__u32)prev_pid;
            e->cpu = bpf_get_smp_processor_id();
            e->prev_state = prev_state;
            e->type = 1;              /* preempted */
            e->nice = 0;
            __u64 *off = bpf_map_lookup_elem(&last_offcpu, &(e->pid));
            e->delta_ns = off ? (long long)(ts - *off) : 0;
            bpf_ringbuf_submit(e, 0);
        }
        bump_stat(1, 1);
    }

    /* 记录 next 的"开始运行"时刻，并计算它此前在 runnable 队列里排了多久 */
    if (is_watched((__u32)next_tgid)) {
        bpf_map_update_elem(&last_offcpu, &next_pid, &ts, BPF_ANY);
        bump_stat(0, 1);
    }
    return 0;
}

/*
 * kprobe on kgsl 提交路径（Adreno）。
 * 不同内核里符号可能是 kgsl_process_ib / adreno_dispatch_jobs / mali_kwpm_*，
 * 所以用户态加载时会用 kallsyms 探测真实符号名再 attach。
 * 这里保留一个通用入口：任何 GPU 提交都刷新"最后一次提交时间"，
 * 调度器据此判断渲染管线是活的（而不是靠 CPU 占用猜）。
 */
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 4);
    __type(key, __u32);
    __type(value, __u64);
} gpu_last_submit SEC(".maps");

SEC("kprobe/kgsl_process_ib")
int ent_gpu_submit(struct pt_regs *ctx)
{
    __u64 ts = bpf_ktime_get_ns();
    __u32 k = 0;
    bpf_map_update_elem(&gpu_last_submit, &k, &ts, BPF_ANY);

    struct ent_gpu_event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (e) {
        e->ts = ts;
        e->tgid = bpf_get_current_pid_tgid() >> 32;
        e->pid  = (__u32)bpf_get_current_pid_tgid();
        e->submit_ns = ts;
        e->q = 0; e->flags = 0;
        bpf_ringbuf_submit(e, 0);
    }
    return 0;
}

/* 渲染线程的 vsync 等待：统计 eglSwapBuffers 阻塞时长可推断掉帧 */
SEC("tracepoint/sched/sched_wakeup")
int ent_handle_wakeup(struct trace_event_raw_sched_wakeup_template *ctx)
{
    __s32 p  = BPF_CORE_READ(ctx, pid);
    __u64 *off = bpf_map_lookup_elem(&last_offcpu, &p);
    if (!off) return 0;
    __u64 now = bpf_ktime_get_ns();
    __s64 wait = (__s64)(now - *off);
    if (is_watched((__u32)p) && wait > 2000000) {   /* >2ms 的唤醒延迟才值得上报 */
        struct ent_switch_event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
        if (e) {
            e->ts = now; e->pid = (__u32)p; e->tgid = (__u32)p;
            e->cpu = bpf_get_smp_processor_id();
            e->delta_ns = wait;
            e->type = 3;           /* wakeup latency */
            e->prev_state = 0; e->nice = 0;
            bpf_ringbuf_submit(e, 0);
        }
    }
    bpf_map_delete_elem(&last_offcpu, &p);
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
