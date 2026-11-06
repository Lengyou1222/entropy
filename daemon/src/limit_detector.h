/*
 * File: daemon/src/limit_detector.h
 *
 * 硬件极限探测引擎。产出两个对用户真正有意义的数：
 *   甜点频率 (sweet spot)  —— perf/W 最高的那一档，日常/游戏档默认贴着它跑；
 *   绝对极限频率 (abs limit) —— 在**不触发熔断**的前提下能稳定住的最频档。
 *
 * 为什么不能简单地"扫最高频然后看会不会炸"：
 *  1. 满载扫频 3 秒就能让 SoC 从 30℃ 冲到 48℃。整轮扫完会直接撞熔断，
 *     用户看到的是"探测失败"而不是数据。所以每一档只做短脉冲(dwell)，
 *     档与档之间插入等长的空闲期，让热容当缓冲。
 *  2. perf/W 的分子必须是可重复的负载，否则横向不可比。这里用固定迭代的
 *     CPU 密集 kernel（乘法链，不会被编译器常量折叠）作为"标尺负载"，
 *     分母用电池侧瞬时功率。
 *
 * 探测本身被当成一种"高危操作"对待：任何时刻 temperature >= cfg.temp_ceiling_mc
 * 或超出 budget_s，立刻 abort、还原、并在结果里标 aborted_by_safety=1。
 */
#ifndef ENTROPY_LIMIT_DETECTOR_H
#define ENTROPY_LIMIT_DETECTOR_H

#include "entropy_ipc.h"
#include "sysfs.h"
#include <stdbool.h>

typedef struct {
    const entropy_hwmap *hw;
    entropy_probe_req    req;
    /* 结果通过回调推给 IPC 层，探测线程不直接碰 socket */
    void (*on_partial)(const entropy_probe_result *r, void *user);
    void (*on_done)(const entropy_probe_result *r, void *user);
    void  *user;
} entropy_probe_ctx;

int  ent_probe_start(const entropy_probe_ctx *ctx);   /* 0 = 已启动；-1 = 已在跑/参数非法 */
int  ent_probe_stop(void);                              /* 请求中止，下一档边界生效 */
bool ent_probe_running(void);
void ent_probe_last_result(entropy_probe_result *out);  /* 供 UI 二次查询 */

/* 供 thermal_monitor 使用：读取所有 zone 的最大真实温度 */
int32_t ent_probe_max_temp_mc(const entropy_hwmap *hw);

/*
 * 被动标定"强制降频温度阈值"：当内核实际生效频率低于我们的请求值、
 * 且此刻温度在上升，就把这一刻记为该机型真实降频点。
 * 这是唯一安全的阈值测量方式 —— 不靠烧机去撞保护线。
 */
int ent_note_forced_throttle(const entropy_hwmap *hw, int32_t requested, int32_t actual);

#endif /* ENTROPY_LIMIT_DETECTOR_H */
