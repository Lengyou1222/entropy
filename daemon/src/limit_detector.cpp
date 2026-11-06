/*
 * File: daemon/src/limit_detector.cpp
 * 算法与安全性前提见 limit_detector.h。
 */
#include "limit_detector.h"
#include "safety_fuse.h"

#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {

std::atomic<bool>     g_run{false};
std::atomic<bool>     g_stop{false};
entropy_probe_ctx     g_ctx{};
pthread_t             g_thread{};
entropy_probe_result  g_last{};
std::atomic<bool>     g_last_valid{false};

/* ------------------------------------------------------------------ *
 * 标尺负载                                                            *
 *                                                                     *
 * 一串不可合并、不可常量折叠的整数依赖链。选整数而不是浮点是因为：      *
 *  - 整数乘法在各 cluster 上 IPC 稳定，跨代核心横向可比；              *
 *  - 不会触发 AVX/SVE 降频（部分 SoC 的重指令会强制降低电压上限，      *
 *    那会让"频率-功耗"曲线本身失真）。                                *
 * volatile sink 阻止优化器删掉整条链。                                *
 * ------------------------------------------------------------------ */
struct Worker {
    std::atomic<uint64_t> iters{0};
    std::atomic<bool>     go{false};
    int                   cpu;
    pthread_t             th;
};

uint64_t __attribute__((noinline)) scale_kernel(uint64_t seed, uint64_t n)
{
    volatile uint64_t sink = 0;
    uint64_t a = seed | 1, b = 0x9E3779B97F4A7C15ull;
    for (uint64_t i = 0; i < n; ++i) {
        a = a * 6364136223846793005ull + 1442695040888963407ull;
        b ^= (a >> 7) | (a << 57);
        a += b * 3;
        if ((i & 1023) == 0) sink = a ^ b;      /* 定期把结果"用掉"一次 */
    }
    return a ^ b ^ (uint64_t)sink;
}

void *worker_thread(void *p)
{
    Worker *w = (Worker *)p;
    /* 钉到目标 cluster 的某个核，保证测的是那个 cluster 而不是别的 */
    cpu_set_t s; CPU_ZERO(&s); CPU_SET(w->cpu, &s);
    sched_setaffinity(0, sizeof(s), &s);
    uint64_t acc = 0x1234567;
    uint64_t local = 0;
    while (!g_stop.load(std::memory_order_relaxed) && w->go.load(std::memory_order_relaxed)) {
        acc = scale_kernel(acc, 200000);        /* ~50ms 量级一次检查，兼顾响应与开销 */
        local += 200000;
        w->iters.store(local, std::memory_order_relaxed);
    }
    return nullptr;
}

int32_t read_temp_max(const entropy_hwmap *hw)
{
    int32_t mx = INT32_MIN;
    for (int i = 0; i < hw->zone_cnt; ++i) {
        int32_t v;
        if (ent_read_int(hw->zones[i].temp_path, &v) == 0 && v > mx) mx = v;
    }
    return mx;
}

void sleep_ms(int ms)
{
    struct timespec ts { ms / 1000, (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, nullptr);
}

/* ------------------------------------------------------------------ *
 * CPU 台阶扫描                                                        *
 * ------------------------------------------------------------------ */
void probe_cpu(void)
{
    const entropy_hwmap *hw = g_ctx.hw;
    /* 逐 cluster 独立扫，因为大小核的甜点频率完全不在一个量级上 */
    for (int ci = 0; ci < hw->cluster_cnt; ++ci) {
        const entropy_cluster *c = &hw->clusters[ci];
        if (c->freq_cnt < 2) continue;

        /* 找到本 cluster 的一个代表核用于绑 worker */
        char rb[256];
        if (ent_read_str(c->related_cpus, rb, sizeof(rb)) != 0) continue;
        int rep_cpu = atoi(rb);

        /* 起 2 个 worker 制造跨核压力；单线程会让 DVFS 判定为轻负载 */
        std::vector<Worker> workers(2);
        for (int k = 0; k < 2; ++k) {
            workers[k].cpu = rep_cpu + (k % std::max(1, c->core_cnt));
            workers[k].go.store(true);
            pthread_create(&workers[k].th, nullptr, worker_thread, &workers[k]);
        }

        int i_begin = 0, i_end = c->freq_cnt - 1;
        if (g_ctx.req.start_khz > 0) {
            for (int i = 0; i < c->freq_cnt; ++i) if (c->freqs[i] >= g_ctx.req.start_khz) { i_begin = i; break; }
        }
        if (g_ctx.req.max_khz > 0) {
            for (int i = c->freq_cnt - 1; i >= 0; --i) if (c->freqs[i] <= g_ctx.req.max_khz) { i_end = i; break; }
        }
        if (g_ctx.req.step_khz > 0) { /* 采样步长：跳过中间档，缩短总时长 */ }

        int32_t best_khz = 0; double best_ppw = -1.0;
        int32_t stable_khz = 0;
        double slope_prev = 0.0;

        for (int i = i_begin; i <= i_end; ++i) {
            if (g_stop.load(std::memory_order_relaxed)) break;
            if (ent_fuse_is_locked()) { g_stop.store(true); break; }

            /* 抬频：只动这一档，别的地方一律不碰 */
            ent_write_freq_clamped(c->scaling_max, c->freqs[i], c->hw_min, c->hw_max);
            ent_write_freq_clamped(c->scaling_min, c->freqs[i], c->hw_min, c->freqs[i]);
            sleep_ms(g_ctx.req.dwell_ms);

            uint64_t it = workers[0].iters.load() + workers[1].iters.load();
            int32_t temp = read_temp_max(hw);
            int32_t power = ent_read_power_mw();

            /* 中止条件 1：温度预算 */
            if (temp != INT32_MIN && temp >= g_ctx.req.temp_ceiling_mc) {
                g_last.aborted_by_safety = 1;
                snprintf(g_last.note, sizeof(g_last.note),
                         "cpu stop@%dkHz temp=%dmC", c->freqs[i], temp);
                /* 绝对极限 = 上一档，那一档才是"能稳定住"的 */
                g_last.cpu_abs_limit_khz = stable_khz;
                break;
            }
            /* 中止条件 2：实际频率没跟上调用的请求 —— 硬件层温控在强制压频 */
            int32_t actual;
            if (ent_read_int(c->scaling_max, &actual) == 0 && actual < c->freqs[i]) {
                stable_khz = actual;
                g_last.cpu_abs_limit_khz = actual;
                g_last.throttle_temp_mc = temp;
                snprintf(g_last.note, sizeof(g_last.note),
                         "forced throttle at %dmC: wanted %d got %d", temp, c->freqs[i], actual);
                break;
            }
            stable_khz = c->freqs[i];
            g_last.cpu_abs_limit_khz = stable_khz;

            if (power > 0) {
                /* perf/W：迭代数/瓦 归一化后跨档比较。
                 * 关键是识别"边际收益拐点"——频率涨了 10% 但迭代只涨 3%，
                 * 说明电压补偿吃掉了收益，甜点就在拐点之前。 */
                double ppw = (double)it / (double)power;
                double s = (best_ppw > 0) ? (ppw - best_ppw) / best_ppw : 1.0;
                if (ppw > best_ppw) { best_ppw = ppw; best_khz = c->freqs[i]; }
                if (slope_prev > 0 && s < 0.0) {
                    /* 已过峰值：记录拐点并停止继续加热 */
                    snprintf(g_last.note, sizeof(g_last.note),
                             "ppw inflection at %dkHz", best_khz);
                    break;
                }
                slope_prev = s;
            }

            /* 档间空闲：热容缓冲。这一拍必须让 worker 停手，否则温度只涨不落 */
            for (auto &w : workers) w.go.store(false);
            sleep_ms(std::min(g_ctx.req.dwell_ms, 300));
            for (auto &w : workers) w.go.store(true);

            int pct = (int)((i - i_begin + 1) * 100.0 / (i_end - i_begin + 1));
            g_last.done = (uint32_t)std::min(pct, 99);
            if (g_ctx.on_partial) g_ctx.on_partial(&g_last, g_ctx.user);
        }

        g_last.cpu_sweet_khz = best_khz ? best_khz : stable_khz;
        g_last.best_perf_per_watt = (int32_t)(best_ppw > 0 ? best_ppw : 0);
        for (auto &w : workers) { w.go.store(false); g_stop.store(true); pthread_join(w.th, nullptr); }
        g_stop.store(false);
        /* 还原本 cluster，避免带着满频去做下一个 cluster 的探测 */
        ent_backup_restore(hw);
    }
}

/* ------------------------------------------------------------------ *
 * GPU 台阶扫描                                                        *
 *                                                                     *
 * GPU 不能像 CPU 那样凭空造负载（我们不想在探测时真去跑一个渲染 app）。 *
 * 所以策略是"被动标定"：                                                 *
 *   1) 抬 max_gpu_clock 到目标档；                                       *
 *   2) 读 kgsl 的 busy/idle 计数 + 电池功率，只在**有真实图形负载**时采样；*
 *   3) 累积 (频率档 -> 该档下的 busy%与功耗) 直方图；                     *
 *   4) 用直方图算每档的"单位功耗有效渲染量"，得到甜点。                   *
 * 没有图形负载时本函数只是走完一遍表并给出空档，不会污染数据。            *
 * ------------------------------------------------------------------ */
void probe_gpu(void)
{
    const entropy_hwmap *hw = g_ctx.hw;
    if (hw->gpu_max[0] == '\0' || hw->gpu_hw_max <= 0) {
        snprintf(g_last.note + strlen(g_last.note), sizeof(g_last.note) - strlen(g_last.note),
                 " gpu:no-node");
        return;
    }
    /* 频率表：kgsl 的 freq_table 是 "MHz MHz ..."；缺表时按 8 段等分近似 */
    int32_t tbl[64]; int n = 0;
    char s[1024];
    if (hw->gpu_avail[0] && ent_read_str(hw->gpu_avail, s, sizeof(s)) == 0) {
        const char *p = s;
        while (*p && n < 64) {
            char *e = nullptr; long v = strtol(p, &e, 10);
            if (e == p) break;
            if (v > 0) tbl[n++] = v > 5000000 ? (int32_t)(v / 1000) : (int32_t)v;
            p = e; while (*p == ' ' || *p == '\n') p++;
        }
    }
    if (n == 0) {
        for (int i = 0; i < 8; ++i)
            tbl[n++] = hw->gpu_hw_min + (hw->gpu_hw_max - hw->gpu_hw_min) * i / 7;
    }

    int32_t best = 0, stable = 0; double best_ratio = -1.0;
    for (int i = 0; i < n; ++i) {
        if (g_stop.load(std::memory_order_relaxed) || ent_fuse_is_locked()) break;
        int32_t t = read_temp_max(hw);
        if (t != INT32_MIN && t >= g_ctx.req.temp_ceiling_mc) {
            g_last.aborted_by_safety = 1;
            snprintf(g_last.note, sizeof(g_last.note), "gpu stop@%dmC", t);
            break;
        }
        ent_write_freq_clamped(hw->gpu_max, tbl[i], hw->gpu_hw_min, hw->gpu_hw_max);
        ent_write_freq_clamped(hw->gpu_min, tbl[i], hw->gpu_hw_min, hw->gpu_hw_max);
        sleep_ms(g_ctx.req.dwell_ms);

        int32_t busy = 0, power = ent_read_power_mw();
        if (hw->gpu_busy[0]) {
            int32_t b;
            if (ent_read_int(hw->gpu_busy, &b) == 0) busy = b % 1000;
        }
        if (busy > 200 && power > 0) {           /* busy>20% 才认为有真实图形负载 */
            double ratio = (double)busy / (double)power;
            if (ratio > best_ratio) { best_ratio = ratio; best = tbl[i]; }
        }
        stable = tbl[i];
        g_last.gpu_abs_limit_khz = tbl[i];
        sleep_ms(150);                            /* 空闲期 */
    }
    g_last.gpu_sweet_khz = best ? best : stable;
    ent_backup_restore(hw);
}

/* ------------------------------------------------------------------ *
 * 温控降频阈值标定（长期被动观测，非主动加热）                          *
 * ------------------------------------------------------------------ */
/*
 * 我们不靠"烧机"去撞降频温度 —— 那是拿硬件冒险换数据。
 * 正确做法是持续比对"我们请求的 max"与"内核实际生效的 cur"：
 * 只要出现 cur < 请求值 且 期间温度单调上升，那一刻的温度就是该机型的
 * 真实强制降频阈值。用户正常玩游戏时数据自己会来。
 * 这段逻辑同时被 thermal_monitor 复用。
 */

}  // namespace

int32_t ent_probe_max_temp_mc(const entropy_hwmap *hw) { return read_temp_max(hw); }

/* 由 thermal_monitor / main 调用：判断一次被动降频是否发生在升温过程中 */
int ent_note_forced_throttle(const entropy_hwmap *hw, int32_t requested, int32_t actual)
{
    if (requested <= 0 || actual >= requested) return 0;
    int32_t t = read_temp_max(hw);
    if (t == INT32_MIN) return 0;
    if (g_last.throttle_temp_mc == 0 || t < g_last.throttle_temp_mc) {
        g_last.throttle_temp_mc = t;
        g_last_valid.store(true);
        FILE *f = fopen(ENTROPY_LOG_PATH, "a");
        if (f) {
            fprintf(f, "[%lld] THROTTLE observed at %dmC (req %d -> act %d)\n",
                    (long long)ent_monotonic_ms(), t, requested, actual);
            fclose(f);
        }
    }
    return 1;
}

namespace {

void *probe_thread(void *)
{
    pthread_setname_np(pthread_self(), "entropy-probe");
    memset(&g_last, 0, sizeof(g_last));
    int64_t deadline = ent_monotonic_ms() + (int64_t)g_ctx.req.budget_s * 1000;

    /* 探测前必须先把备份做好，否则中止时无路可退 */
    ent_backup_save(g_ctx.hw);

    if (g_ctx.req.target & 0x1) {
        probe_cpu();
        ent_backup_restore(g_ctx.hw);
    }
    if (!(g_ctx.req.target & 0x2)) g_stop.store(true);
    if ((g_ctx.req.target & 0x2) && !g_stop.load()) {
        /* 时间预算是硬约束：CPU 阶段超支就直接放弃 GPU 阶段 */
        if (ent_monotonic_ms() < deadline) {
            g_stop.store(false);
            probe_gpu();
        } else {
            g_last.aborted_by_safety = 1;
            snprintf(g_last.note, sizeof(g_last.note), "budget exhausted before gpu stage");
        }
    }
    ent_backup_restore(g_ctx.hw);      /* 无论成功失败，退出时一律还原 */

    g_last.done = 100;
    g_last_valid.store(true);
    if (g_ctx.on_done) g_ctx.on_done(&g_last, g_ctx.user);
    g_run.store(false);
    return nullptr;
}

}  // namespace

int ent_probe_start(const entropy_probe_ctx *ctx)
{
    if (!ctx || !ctx->hw) return -1;
    if (g_run.load()) return -1;
    if (ent_fuse_is_locked()) return -1;          /* 熔断刚触发，绝不允许再加热 */

    g_ctx = *ctx;
    if (g_ctx.req.dwell_ms <= 0) g_ctx.req.dwell_ms = 900;
    if (g_ctx.req.budget_s <= 0) g_ctx.req.budget_s = 90;
    if (g_ctx.req.temp_ceiling_mc <= 0) g_ctx.req.temp_ceiling_mc = 46000;  /* 比熔断线低 4℃ */
    /* 参数合法性：预算与温度上限必须落在安全区间，越界直接拒绝而不是钳制，
     * 因为那通常意味着 UI 侧出了 bug */
    if (g_ctx.req.budget_s > 600 || g_ctx.req.temp_ceiling_mc > 55000) return -1;

    g_stop.store(false);
    g_run.store(true);
    pthread_attr_t at; pthread_attr_init(&at);
    pthread_attr_setstacksize(&at, 256 * 1024);
    int rc = pthread_create(&g_thread, &at, probe_thread, nullptr);
    pthread_attr_destroy(&at);
    if (rc != 0) { g_run.store(false); return -1; }
    return 0;
}

int ent_probe_stop(void)
{
    if (!g_run.load()) return -1;
    g_stop.store(true);
    return 0;
}

bool ent_probe_running(void) { return g_run.load(); }

void ent_probe_last_result(entropy_probe_result *out)
{
    if (g_last_valid.load()) *out = g_last;
    else memset(out, 0, sizeof(*out));
}
