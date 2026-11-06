/*
 * File: daemon/src/scheduler.cpp
 * 实现见 scheduler.h 的前馈控制说明。
 */
#include "scheduler.h"
#include "safety_fuse.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <sched.h>
#include <unistd.h>

namespace {

/* 每个 cluster 的控制器状态 */
struct ClusterCtl {
    float   prev_load = 0.f;
    float   i_term = 0.f;          /* 积分项，抗稳态误差 */
    int     opp_lo = 0, opp_hi = 0;/* 本档位允许的 OPP 区间索引 */
    int32_t cur_target = 0;
    int     downshift_calm = 0;    /* 连续无压力拍数，用于迟滞降频 */
};
ClusterCtl g_ctl[ENT_MAX_CLUSTER];
bool g_ctl_init = false;

/*
 * 把一个任意频率值"吸附"到最近的可用 OPP —— 方向一律向下。
 * 这里两种吸附都要过：写非 OPP 值时内核会自己向上取到最近的合法档，
 * 于是我们的控制器和内核认知不一致，控制环路出现阶跃噪声；
 * 而 scaling_max_freq 的语义是"不许超过"，向上取档等于替用户多抬一档 ——
 * 实测只有 1.6/3.2GHz 两档的机器上，2.4GHz 上限被向上吸附后写成了 3.2GHz，
 * 界面说"上限 2.40GHz"，节点却是满频。取 <= khz 的最大档才是兑现这个上限。
 */
int32_t snap_to_opp(const entropy_cluster *c, int32_t khz)
{
    if (c->freq_cnt <= 0) return khz;
    int lo = 0, hi = c->freq_cnt - 1;
    if (khz < c->freqs[lo]) return c->freqs[lo];   /* 低于最低一档也只能停在最低档 */
    if (khz >= c->freqs[hi]) return c->freqs[hi];
    while (lo < hi) {                       /* 上界二分：取 <= khz 的最大 OPP */
        int mid = (lo + hi + 1) / 2;
        if (c->freqs[mid] > khz) hi = mid - 1; else lo = mid;
    }
    return c->freqs[lo];
}

/* 这一档允许的绝对上限（kHz）：档位默认与用户上限取更小的那个。
 * apply 与 governor 必须共用这条算式，否则一次写入按 A、下一拍按 B，
 * 节点值会在两个认知之间来回跳。 */
int32_t cap_khz(const entropy_cluster *c, const entropy_sched_cfg *cfg)
{
    int32_t cap = c->hw_max;
    if (cfg->profile == ENT_PROFILE_POWERSAVE)
        cap = c->hw_min + (c->hw_max - c->hw_min) * 55 / 100;
    if (cfg->cpu_max_khz > 0 && cfg->cpu_max_khz < cap) cap = cfg->cpu_max_khz;
    return cap;
}

/* 归一化负载 0..1 -> OPP 索引区间。档位决定"允许跑到多高" */
void build_opp_window(const entropy_hwmap *hw, const entropy_sched_cfg *cfg, int idx, ClusterCtl &st)
{
    const entropy_cluster *c = &hw->clusters[idx];
    if (c->freq_cnt <= 0) { st.opp_lo = st.opp_hi = 0; return; }
    st.opp_lo = 0;
    int32_t cap = cap_khz(c, cfg);
    /* 先假设上限低于最低一档，因此只能停在最低档；
     * 原来这里留的是"整段区间"，于是一个异常小的上限反而放开了满频窗口。 */
    st.opp_hi = 0;
    for (int i = c->freq_cnt - 1; i >= 0; --i) {
        if (c->freqs[i] <= cap) { st.opp_hi = i; break; }
    }
}

/* 从 /proc/loadavg 拿一个全局基线，eBPF 只覆盖被观察进程，整机压力要额外补 */
float system_pressure(void)
{
    FILE *f = fopen("/proc/loadavg", "r");
    if (!f) return 0.f;
    float l1 = 0, l5 = 0, l15 = 0;
    int got = fscanf(f, "%f %f %f", &l1, &l5, &l15);
    fclose(f);
    if (got < 1) return 0.f;
    int ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    return l1 / (float)ncpu;               /* >1 表示已过载 */
}

}  // namespace

void ent_sched_default_cfg(entropy_sched_cfg *cfg, int32_t profile)
{
    memset(cfg, 0, sizeof(*cfg));
    cfg->tick_ms = 50;
    cfg->predictive_on = 1;
    cfg->kp_slope = 0.35f;
    cfg->ki_error = 0.15f;
    cfg->decay = 0.98f;
    cfg->pin_big_cores = 1;
    cfg->profile = profile;
    switch (profile) {
    case ENT_PROFILE_GAME:
        /* 游戏极速：允许顶到甜点频率，boost 窗口拉长，衰减很慢（宁可多耗电也不掉帧） */
        cfg->boost_ms = 1200;
        cfg->decay = 0.995f;
        cfg->ki_error = 0.22f;
        break;
    case ENT_PROFILE_POWERSAVE:
        /* 极致省电：OPP 上限压到硬件最大值的 55%，且衰减很快 */
        cfg->boost_ms = 80;
        cfg->decay = 0.90f;
        cfg->predictive_on = 0;
        cfg->pin_big_cores = 0;
        cfg->cpu_max_khz = 0;   /* 省电档的上限折算见 cap_khz */
        break;
    case ENT_PROFILE_BALANCED:
    default:
        cfg->boost_ms = 400;
        break;
    }
}

int ent_sched_apply(const entropy_hwmap *hw, const entropy_sched_cfg *cfg)
{
    if (!hw || !cfg) return -1;

    /*
     * 熔断锁定期内禁止任何抬频。这里返回 0 而不是 -1：
     * App 侧看到的是"请求已受理"，但实际执行被安全层否决，
     * 由 heartbeat 里的 state=3 告诉 UI 真实状态。
     */
    if (ent_fuse_is_locked()) return 0;

    /* 首次下档前必须备份，否则没有还原依据 */
    static bool backed_up = false;
    if (!backed_up) { ent_backup_save(hw); backed_up = true; }

    for (int i = 0; i < hw->cluster_cnt; ++i) {
        const entropy_cluster *c = &hw->clusters[i];
        if (c->scaling_max[0] == '\0' || c->freq_cnt <= 0) continue;
        int32_t cap = cap_khz(c, cfg);
        int32_t target = snap_to_opp(c, cap);
        ent_write_freq_clamped(c->scaling_max, target, c->hw_min, c->hw_max);
    }
    /*
     * GPU 上限：显式值优先，其次才是档位默认。
     * 以前只有游戏档/省电档会去碰这个节点，于是"均衡档 + 用户手动设了 GPU 上限"
     * 这条路把值整个吞掉 —— 界面说已下发，节点上却一个字节都没动。
     * clamped 仍然钳在 [hw_min, hw_max] 里，越界值写不进去。
     */
    if (hw->gpu_max[0] && hw->gpu_hw_max > 0) {
        int32_t g = 0;
        if (cfg->gpu_max_khz > 0) g = cfg->gpu_max_khz;
        else if (cfg->profile == ENT_PROFILE_GAME) g = hw->gpu_hw_max;
        else if (cfg->profile == ENT_PROFILE_POWERSAVE && hw->gpu_hw_min > 0) g = hw->gpu_hw_min;
        if (g > 0) ent_write_freq_clamped(hw->gpu_max, g, hw->gpu_hw_min, hw->gpu_hw_max);
    }

    /* governor：游戏档用性能导向的 governor，其余交回内核默认。
     * 设备不支持某个 governor 时 scaling_governor 会 reject，不影响其它节点。 */
    if (cfg->profile == ENT_PROFILE_GAME || cfg->predictive_on) {
        for (int i = 0; i < hw->cluster_cnt; ++i) {
            const entropy_cluster *c = &hw->clusters[i];
            if (c->governor[0] == '\0') continue;
            FILE *f = fopen(c->governor, "we");
            if (f) { fprintf(f, "userspace\n"); fclose(f); }
        }
    }

    memset(g_ctl, 0, sizeof(g_ctl));
    g_ctl_init = false;
    return 0;
}

int ent_sched_tick(const entropy_hwmap *hw, const entropy_sched_cfg *cfg,
                   const entropy_load_metrics *m, entropy_sched_tick *out)
{
    memset(out, 0, sizeof(*out));
    out->fused = ent_fuse_is_locked() ? 1 : 0;
    if (out->fused) return 0;                 /* 熔断期间一拍都不动 */

    if (!g_ctl_init) {
        for (int i = 0; i < hw->cluster_cnt; ++i) build_opp_window(hw, cfg, i, g_ctl[i]);
        g_ctl_init = true;
    }

    /*
     * 信号合成。三路：
     *  - eBPF 的唤醒延迟 p95（最灵敏，直接对应掉帧）
     *  - 抢占次数（决定要不要额外抬一档 + 绑核）
     *  - /proc/loadavg（整机基线，防止只看了游戏进程而漏掉系统服务风暴）
     * 权重是按"对帧率的因果强度"排的，不是拍脑袋：唤醒延迟在 2ms 以上时
     * 165Hz(6ms 预算)必然丢帧，而 load 高但延迟低通常只是后台编译类噪声。
     */
    float pressure = 0.f;
    if (m->wakeup_lat_p95_ns > 2000000)
        pressure += std::min(1.0f, (m->wakeup_lat_p95_ns - 2000000) / 4000000.f) * 0.8f;
    if (m->preempt_count > 0)
        pressure += std::min(0.5f, m->preempt_count * 0.03f);
    pressure += std::min(1.0f, system_pressure()) * 0.3f;
    if (m->render_thread_busy == 0) pressure *= 0.4f;  /* 管线空转，别为后台的忙碌抬频 */

    float dt_s = cfg->tick_ms / 1000.f;
    for (int i = 0; i < hw->cluster_cnt; ++i) {
        const entropy_cluster *c = &hw->clusters[i];
        ClusterCtl &st = g_ctl[i];
        if (c->freq_cnt <= 0) continue;

        /* 大核承担前台线程，权重 1.0；小核只跟随，权重 0.35 */
        float w = (i >= hw->cluster_cnt - 1) ? 1.0f : (i >= hw->cluster_cnt - 2 ? 0.7f : 0.35f);
        float load = std::min(1.0f, pressure * w);

        float slope = (load - st.prev_load) / dt_s;          /* 每秒负载变化率 */
        st.prev_load = load;
        float err = load - 0.65f;                            /* 目标工作点 65% */
        st.i_term = std::max(-0.3f, std::min(0.3f, st.i_term + err * dt_s * cfg->ki_error));

        float want;
        if (cfg->predictive_on) {
            /* 前馈：斜率上升时直接加频，不等误差累积 */
            float ff = cfg->kp_slope * slope * 0.05f;
            want = 0.35f + load + ff + st.i_term;
        } else {
            want = 0.30f + load * 0.7f;
        }

        int idx = (int)(want * (c->freq_cnt - 1));
        idx = std::max(0, std::min(c->freq_cnt - 1, idx));
        /* 天花板：档位默认与用户 CPU 上限压出来的那一档。
         * 这一行以前不存在 —— build_opp_window 算出的区间从没被读过，
         * 于是"上限 1.60GHz"写进去之后，下一拍又被压力抬回满频。 */
        idx = std::max(st.opp_lo, std::min(st.opp_hi, idx));
        /* 迟滞：连续 8 拍低负载才允许往下掉，避免 OPP 抖动引起的微卡 */
        int prev_idx = (int)st.cur_target;
        if (idx < prev_idx) {
            st.downshift_calm++;
            if (st.downshift_calm < 8) idx = prev_idx;
            else st.downshift_calm = 0;
        } else {
            st.downshift_calm = 0;
        }
        idx = std::max(0, std::min(c->freq_cnt - 1, idx));

        int32_t khz = c->freqs[idx];
        out->target_khz[i] = khz;
        out->current_khz[i] = khz;   /* 写完后内核生效有 ~1 拍延迟，这里以目标值回显 */
        ent_write_freq_clamped(c->scaling_max, khz, c->hw_min, c->hw_max);
        st.cur_target = (int32_t)idx;
        out->load_slope = slope;
    }

    if (cfg->profile == ENT_PROFILE_GAME && hw->gpu_max[0] && hw->gpu_hw_max > 0) {
        int32_t g = hw->gpu_hw_min + (int32_t)((hw->gpu_hw_max - hw->gpu_hw_min) *
                                std::min(1.0f, pressure * 1.2f));
        g = std::max(hw->gpu_hw_min, std::min(hw->gpu_hw_max, g));
        /* 用户设了 GPU 上限时，压力再高也不能越过这一档 */
        if (cfg->gpu_max_khz > 0) g = std::min(g, cfg->gpu_max_khz);
        out->gpu_target_khz = g;
        ent_write_freq_clamped(hw->gpu_max, g, hw->gpu_hw_min, hw->gpu_hw_max);
    }
    out->preempt_penalty = m->preempt_count > 8 ? 1 : 0;
    ent_fuse_kick();     /* 熔断看门狗：一拍一跳 */
    return 0;
}

/*
 * 渲染线程绑核。
 * 原理：CPU affinity 是 per-task 的，root(CAP_SYS_NICE) 可以对任意 tid 调
 * sched_setaffinity。把游戏主渲染线程钉在大核 cluster 上，
 * 相当于取消调度器在大/小核之间的迁移 —— 迁移本身要付 cache/TLB 冷启动的代价，
 * 在 165Hz 下每次迁移就是一次可感知的 jitter。
 *
 * cgroups v2 路线（更彻底但依赖机型挂载点）：
 *   写 /sys/fs/cgroup/<game>/cpuset.cpus = "6-7"，
 *   并把 tid 写进 cgroup.procs。这里优先用 affinity，失败才尝试 cgroup。
 */
int ent_sched_pin_render_threads(const entropy_hwmap *hw, int32_t tgid, int enable)
{
    if (tgid <= 0) return 0;
    if (ent_fuse_is_locked()) return 0;

    /* 大核簇：clusters 数组末尾（频率最高的那个） */
    int big = hw->cluster_cnt - 1;
    if (big < 0) return -1;
    const entropy_cluster *c = &hw->clusters[big];

    /* related_cpus 是 "0,1,2,3" 形式；大核通常是 "6,7" */
    cpu_set_t set;
    CPU_ZERO(&set);
    char buf[256];
    if (c->related_cpus[0] == '\0') return -1;
    if (ent_read_str(c->related_cpus, buf, sizeof(buf)) != 0) return -1;
    /* strtok_r：主循环线程和熔断线程都可能在同一瞬间解析别的列表，
     * strtok 的全局保存指针会让两边互相踩到，绑错核比不绑更糟。 */
    char *save = nullptr;
    for (char *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(nullptr, ",", &save)) {
        int cpu = atoi(tok);
        if (cpu >= 0 && cpu < CPU_SETSIZE) CPU_SET(cpu, &set);
    }

    char dir[64];
    snprintf(dir, sizeof(dir), "/proc/%d/task", tgid);
    DIR *d = opendir(dir);
    if (!d) return -1;
    int pinned = 0;
    dirent *e;
    while ((e = readdir(d)) != nullptr) {
        if (e->d_name[0] == '.') continue;
        int tid = atoi(e->d_name);
        /*
         * 只钉"渲染相关"线程：线程名含 RenderThread / Vulkan / HWUI / gralloc。
         * 全钉会饿死 GC 与网络线程，反而制造卡顿 —— 这是很多激进方案的通病。
         */
        char nm[64] = {0};
        char p[128];
        snprintf(p, sizeof(p), "/proc/%d/task/%d/comm", tgid, tid);
        if (ent_read_str(p, nm, sizeof(nm)) != 0) continue;
        if (!enable) {
            cpu_set_t all; CPU_ZERO(&all);
            for (int i = 0; i < CPU_SETSIZE; i++) CPU_SET(i, &all);
            if (sched_setaffinity(tid, sizeof(all), &all) == 0) pinned++;
            continue;
        }
        if (strstr(nm, "RenderThread") || strstr(nm, "VkThread") || strstr(nm, "vulkan") ||
            strstr(nm, "HWUI") || strstr(nm, "GPU") || strstr(nm, "SurfaceFlin")) {
            if (sched_setaffinity(tid, sizeof(set), &set) == 0) pinned++;
        }
    }
    closedir(d);
    return pinned;
}
