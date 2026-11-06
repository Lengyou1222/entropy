/*
 * File: daemon/src/scheduler.h
 *
 * 预测性调度核心。对外只有三个动作：设档位、喂一帧信号、跑一拍。
 *
 * 与 Uperf/Scene 这类现有方案的本质差别：
 *   它们是「反应式」—— 拿到当前 util 后查表改频率，改完已经过了 1~2 个调度周期，
 *   掉帧已经发生了。我们做的是「斜率驱动的前馈」：
 *      f(t+Δ) = clamp( f(t) + Kp·(load(t)-load(t-1))/Δt + Ki·e(t) )
 *   其中 Δ 固定 50ms（≈3 个 60Hz 帧、7 个 165Hz 帧），用 eBPF 的
 *   唤醒延迟与抢占次数作为 e(t) 的修正项。负载还在上升时就先把 OPP 抬上去，
 *   等真正需要满频时硬件已经就位 —— 这是"提前 50ms"这个指标的实现方式。
 */
#ifndef ENTROPY_SCHEDULER_H
#define ENTROPY_SCHEDULER_H

#include "entropy_ipc.h"
#include "ebpf_loader.h"
#include "sysfs.h"

typedef struct {
    int32_t  profile;
    int32_t  cpu_max_khz;        /* 解析后的实际生效上限 */
    int32_t  gpu_max_khz;
    int32_t  boost_ms;
    int32_t  predictive_on;

    /* 前馈控制参数，默认值针对 Adreno/Immortalis + 165Hz 屏调过 */
    float    kp_slope;           /* 斜率增益，0.35 */
    float    ki_error;           /* 稳态误差增益，0.15 */
    float    decay;              /* 无压力时的每拍降频系数，0.98 */
    int32_t  tick_ms;            /* 一拍的长度，50 */
    int32_t  pin_big_cores;      /* 是否把渲染线程绑到大核 */
} entropy_sched_cfg;

void ent_sched_default_cfg(entropy_sched_cfg *cfg, int32_t profile);

/* 档位切换；内部会先做一次备份，保证任何时刻都能还原 */
int  ent_sched_apply(const entropy_hwmap *hw, const entropy_sched_cfg *cfg);

/* 一拍：消费 eBPF 信号，更新每 cluster 目标频率并落地。返回本拍摘要 */
typedef struct {
    int32_t target_khz[ENT_MAX_CLUSTER];
    int32_t current_khz[ENT_MAX_CLUSTER];
    int32_t gpu_target_khz;
    float   load_slope;          /* 便于 UI 画曲线 */
    int32_t preempt_penalty;     /* 本拍因抢占额外抬高的 OPP 数 */
    int32_t fused;               /* 被熔断锁定，本拍未抬频 */
} entropy_sched_tick;

int  ent_sched_tick(const entropy_hwmap *hw, const entropy_sched_cfg *cfg,
                    const entropy_load_metrics *m, entropy_sched_tick *out);

/* 前台渲染线程绑核：把 target tgid 的主渲染线程钉在最后 core_cnt 个核上 */
int  ent_sched_pin_render_threads(const entropy_hwmap *hw, int32_t tgid, int enable);

#endif /* ENTROPY_SCHEDULER_H */
