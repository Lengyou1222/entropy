/*
 * File: daemon/src/safety_fuse.h
 *
 * 安全熔断（Safety Fuse）。
 *
 * 为什么必须独立成模块并且独立成线程：
 *  调度主循环、eBPF 回调、探测引擎都可能在某次 sysfs 写入上阻塞，或者在
 *  负载斜率异常时进入"一直抬频"的正反馈。任何跑在那些线程里的保护逻辑都
 *  不可信。熔断线程只做三件事：读温度、检查心跳是否停更、必要时回写默认值。
 *  它不 import 调度器的任何状态，也就不可能被调度器的 bug 拖死。
 */
#ifndef ENTROPY_SAFETY_FUSE_H
#define ENTROPY_SAFETY_FUSE_H

#include "sysfs.h"
#include <stdint.h>

typedef enum {
    ENT_FUSE_OK = 0,
    ENT_FUSE_SOFT_LIMIT,     /* 超过软上限，主动降档（不停机） */
    ENT_FUSE_TEMP_BREACH,    /* 超过绝对熔断温度 -> reset_to_default() */
    ENT_FUSE_MAIN_STALL,     /* 主循环心跳停更 > 2s，疑似死锁 */
    ENT_FUSE_SELF_OVERLOAD,  /* Daemon 自身 CPU 连续两窗超预算，说明采样逻辑跑飞了 */
    ENT_FUSE_PROBE_BUDGET,   /* 探测超出时间预算 */
} entropy_fuse_reason;

/*
 * 自检预算：10s 窗口的平均占用上限（千分比），且要连续两窗超限才动作。
 *
 * 依据是实测，不是估的：同一份主循环（10ms 一拍 + 每拍若干次 sysfs 读写）
 * 在真 ARM 机器上约 1%，在带 ARM 转译（houdini / ndk_translation）的机型上
 * 实测 5.6%。原来写死的 5% 会把后者天天判成失控 —— 一个天天自熔断的守护进程
 * 等于没有守护进程。150‰ 既能容下慢机器的正常开销，又拦得住真正跑飞的 100%。
 */
#define ENT_SELF_CPU_BUDGET_PERMILLE 150

typedef struct {
    int32_t fuse_temp_mc;        /* 默认 85000；上限 ENT_FUSE_MAX_MC=95000 */
    int32_t soft_limit_mc;       /* 默认 70000，必须低于熔断线 */
    int32_t poll_interval_ms;    /* 默认 1000；温度采样不需要更密 */
    int32_t stall_limit_ms;      /* 主循环心跳停更判定 */
    int32_t self_cpu_permille_max;
    int     auto_restore;        /* 熔断后是否自动还原 */
} entropy_fuse_cfg;

typedef void (*ent_fuse_callback)(entropy_fuse_reason reason, int32_t detail, void *user);

/* 启动熔断线程。hw 必须在进程生命周期内有效（静态全局即可）。 */
int  ent_fuse_start(const entropy_hwmap *hw, const entropy_fuse_cfg *cfg,
                    ent_fuse_callback cb, void *user);
void ent_fuse_stop(void);

/* 主循环每轮调用一次，等于喂狗 */
void ent_fuse_kick(void);

/* 手动触发全量还原；探测引擎与 IPC 的 RESET 命令都走这里，保证只有一条还原路径 */
void ent_fuse_reset_to_default(const char *why);

/* 当前是否处于熔断锁定状态；锁定期内调度器禁止任何抬频写入 */
int  ent_fuse_is_locked(void);
entropy_fuse_reason ent_fuse_last_reason(void);

/* 熔断线程自己的 CPU 占用（千分比），用于自检 */
int  ent_fuse_self_cpu_permille(void);

#endif /* ENTROPY_SAFETY_FUSE_H */
