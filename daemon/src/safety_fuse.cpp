/*
 * File: daemon/src/safety_fuse.cpp
 *
 * 实现要点见 safety_fuse.h。整个文件刻意不用任何堆分配、不加锁、不打日志以外的系统调用，
 * 让它的执行路径短到不可能阻塞：
 *   read(/sys/.../temp) -> 比较 -> write(/sys/...) -> log
 * 全部是 O(1) 且带 O_NONBLOCK。
 */
#include "safety_fuse.h"
#include "entropy_ipc.h"

#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>

namespace {

struct FuseState {
    const entropy_hwmap *hw         = nullptr;
    entropy_fuse_cfg     cfg{};
    ent_fuse_callback    cb         = nullptr;
    void                *user       = nullptr;

    /* 与主循环之间只用 atomic 通信，避免互斥锁本身成为死锁源 */
    std::atomic<int64_t> last_kick_ms{0};
    std::atomic<int>     locked{0};
    std::atomic<int>     reason{ENT_FUSE_OK};
    std::atomic<int>     stop_req{0};
    std::atomic<int64_t> lock_until_ms{0};

    /* 自身 CPU 采样：只能按秒级窗口统计，理由见 fuse_thread 里的注释 */
    int64_t self_win_wall_ms = 0;
    int64_t self_win_cpu_ms = 0;
    int self_over_streak = 0;
    std::atomic<int> self_permille{0};

    /* 软降档节流：一次降档后要冷静 5s，否则会在阈值附近来回抖 */
    int64_t last_soft_action_ms = 0;

    pthread_t thread{};
    bool started = false;
};

FuseState g_fuse;

/* 自身占用自检的窗口长度与连击次数：见 fuse_thread 的注释 */
constexpr int64_t SELF_CPU_WINDOW_MS = 10000;
constexpr int SELF_CPU_TRIP_STREAK = 2;

/* /proc/self/stat 里 utime(14) 与 stime(15) 之和，换算成毫秒。
 * 用 jiffies/HZ 而不是 clock_gettime，因为这正是内核记账的口径 ——
 * 我们要判断的是"调度逻辑有没有把自己跑飞"，口径必须和调度器一致。 */
int64_t self_cpu_time_ms()
{
    FILE *f = fopen("/proc/self/stat", "r");
    if (!f) return 0;
    char line[1024];
    int64_t ut = 0, st = 0;
    if (fgets(line, sizeof(line), f)) {
        /* comm 字段可能带空格，必须从最后一个 ')' 之后开始切 */
        char *p = strrchr(line, ')');
        if (p) {
            int fld = 0;
            char *save = nullptr;
            for (char *tok = strtok_r(p + 1, " ", &save); tok; tok = strtok_r(nullptr, " ", &save)) {
                fld++;
                if (fld == 12) ut = atoll(tok);   /* utime  = 字段14，) 之后第12个 */
                if (fld == 13) { st = atoll(tok); break; }
            }
        }
    }
    fclose(f);
    long hz = sysconf(_SC_CLK_TCK);
    if (hz <= 0) hz = 100;
    return (ut + st) * 1000 / hz;
}

int32_t max_zone_temp(const entropy_hwmap *hw)
{
    /*
     * 取所有可读 zone 的最大值，而不是某个"看起来像 SoC"的 zone。
     * 理由：厂商命名千奇百怪（skill-thm / qqcta / gpu-therm / TE-A），
     * 猜错一个就把保护关掉了。取 max 永远不会低估风险，最多是过早降档 ——
     * 过早降档损性能，过晚降档损硬件，两者不可同日而语。
     */
    int32_t mx = INT32_MIN;
    for (int i = 0; i < hw->zone_cnt; ++i) {
        int32_t v;
        if (ent_read_int(hw->zones[i].temp_path, &v) == 0 && v > mx) mx = v;
    }
    return mx;
}

void fuse_log(const char *level, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

void fuse_log(const char *level, const char *fmt, ...)
{
    FILE *f = fopen(ENTROPY_LOG_PATH, "a");
    if (!f) return;
    char msg[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    fprintf(f, "[%lld] FUSE/%s %s\n", (long long)ent_monotonic_ms(), level, msg);
    fclose(f);
}

/* 熔断后的核心动作：把频率交回内核自己管 */
void do_reset(const char *why, entropy_fuse_reason r)
{
    if (!g_fuse.hw) return;
    fuse_log("CRIT", "reset_to_default() invoked: %s", why);

    /*
     * 顺序很重要：
     *  1) 先把 scaling_max 拉回硬件最低档，立刻减少发热（比备份还原更快生效）；
     *  2) 再逐节点回写开机备份值；
     *  3) 最后把 governor 交回 schedutil，让内核重新接管。
     * 反过来做的话，还原备份的过程中还会有一小段满频窗口。
     */
    for (int i = 0; i < g_fuse.hw->cluster_cnt; ++i) {
        const entropy_cluster *c = &g_fuse.hw->clusters[i];
        if (c->hw_min > 0) ent_write_int(c->scaling_max, c->hw_min);
    }
    if (g_fuse.hw->gpu_min[0] && g_fuse.hw->gpu_hw_min > 0)
        ent_write_int(g_fuse.hw->gpu_min, g_fuse.hw->gpu_hw_min);

    ent_backup_restore(g_fuse.hw);

    for (int i = 0; i < g_fuse.hw->cluster_cnt; ++i) {
        const entropy_cluster *c = &g_fuse.hw->clusters[i];
        if (c->governor[0]) {
            int fd = open(c->governor, O_WRONLY | O_CLOEXEC | O_NONBLOCK);
            if (fd >= 0) { const char *g = "schedutil\n"; ssize_t w = write(fd, g, strlen(g)); (void)w; close(fd); }
        }
    }

    g_fuse.reason.store(r, std::memory_order_release);
    /* 锁定 120s：给设备降温的时间，也防止 App 立刻又下发同一个高危档位 */
    g_fuse.locked.store(1, std::memory_order_release);
    g_fuse.lock_until_ms.store(ent_monotonic_ms() + 120000, std::memory_order_release);
    fuse_log("CRIT", "locked for 120s, reason=%d", (int)r);

    if (g_fuse.cb) g_fuse.cb(r, max_zone_temp(g_fuse.hw), g_fuse.user);
}

void *fuse_thread(void *)
{
    pthread_setname_np(pthread_self(), "entropy-fuse");
    /*
     * 提升熔断线程优先级：主循环若因大量 sysfs 写入而饱和，熔断线程必须仍能
     * 抢到 CPU。SCHED_FIFO 需要 root，Magisk 场景下可用；失败则静默保留其它。
     */
    sched_param sp{ .sched_priority = 20 };
    if (sched_setscheduler(0, SCHED_FIFO, &sp) != 0)
        fuse_log("WARN", "SCHED_FIFO unavailable: %s", strerror(errno));

    int64_t last_temp_ms = 0;
    int32_t last_temp = INT32_MIN;

    while (!g_fuse.stop_req.load(std::memory_order_relaxed)) {
        struct timespec rq = { 0, 50 * 1000 * 1000 };   /* 50ms 周期，比温度变化快一个数量级 */
        nanosleep(&rq, nullptr);
        int64_t now = ent_monotonic_ms();

        /* --- 1. 主循环心跳 --- */
        int64_t kick = g_fuse.last_kick_ms.load(std::memory_order_acquire);
        if (kick != 0 && now - kick > g_fuse.cfg.stall_limit_ms) {
            do_reset("main loop stalled", ENT_FUSE_MAIN_STALL);
            g_fuse.last_kick_ms.store(now, std::memory_order_release);  /* 重新计时，避免刷屏 */
        }

        /* --- 2. 自身占用自检（秒级窗口 + 连续两窗才动作） ---
         *
         * 为什么不能一拍一判：/proc/self/stat 的 utime/stime 以 jiffie 为单位，
         * 本机 1 jiffie = 10ms，而熔断线程 50ms 一拍。窗口只比记账粒度大 5 倍，
         * dc 只能取 0/10/20/30ms 这种台阶，一次 10ms 的正常节点写入就是 200‰，
         * 开机那 0.3s 的硬件扫描 + 备份更是直接顶满 —— 于是"启动"本身被判成失控，
         * 每拍 reset 一次；而 reset 要写十几个节点，占用被自己越查越高（正反馈）。
         * 判"1%~5% 的稳态预算"必须用远大于粒度的窗口，且要连续超两窗才算失控。
         */
        int64_t cpu = self_cpu_time_ms();
        if (g_fuse.self_win_wall_ms == 0) {
            g_fuse.self_win_wall_ms = now;
            g_fuse.self_win_cpu_ms = cpu;
        }
        if (now - g_fuse.self_win_wall_ms >= SELF_CPU_WINDOW_MS) {
            int64_t win = now - g_fuse.self_win_wall_ms;
            int permille = (int)((cpu - g_fuse.self_win_cpu_ms) * 1000 / win);
            g_fuse.self_permille.store(permille, std::memory_order_relaxed);
            /* 锁定期内不重复判：reset 已经做过，再来一次只会加重占用 */
            if (g_fuse.locked.load(std::memory_order_relaxed)) {
                g_fuse.self_over_streak = 0;
            } else if (permille > g_fuse.cfg.self_cpu_permille_max) {
                if (++g_fuse.self_over_streak >= SELF_CPU_TRIP_STREAK) {
                    g_fuse.self_over_streak = 0;
                    do_reset("daemon self-overload", ENT_FUSE_SELF_OVERLOAD);
                }
            } else {
                g_fuse.self_over_streak = 0;
            }
            g_fuse.self_win_wall_ms = now;
            g_fuse.self_win_cpu_ms = cpu;
        }

        /* --- 3. 温度：1s 一次足够（热时间常数是秒级，采更快只会白耗 CPU） --- */
        if (now - last_temp_ms >= g_fuse.cfg.poll_interval_ms) {
            last_temp_ms = now;
            last_temp = max_zone_temp(g_fuse.hw);
        }

        if (last_temp == INT32_MIN) continue;   /* 读不到温度绝不等于安全，交给主循环上报 */

        if (last_temp >= g_fuse.cfg.fuse_temp_mc) {
            char w[128];
            snprintf(w, sizeof(w), "temperature %" PRId32 "mC >= fuse %" PRId32 "mC",
                     last_temp, g_fuse.cfg.fuse_temp_mc);
            do_reset(w, ENT_FUSE_TEMP_BREACH);
        } else if (g_fuse.locked.load(std::memory_order_relaxed) &&
                   now >= g_fuse.lock_until_ms.load(std::memory_order_relaxed)) {
            /* 冷静期结束且温度确实回落，才解锁 */
            if (last_temp < g_fuse.cfg.fuse_temp_mc - 3000) {
                g_fuse.locked.store(0, std::memory_order_release);
                fuse_log("INFO", "unlocked at %" PRId32 "mC", last_temp);
            } else {
                g_fuse.lock_until_ms.store(now + 30000, std::memory_order_relaxed);
            }
        } else if (!g_fuse.locked.load(std::memory_order_relaxed) &&
                   last_temp >= g_fuse.cfg.soft_limit_mc &&
                   now - g_fuse.last_soft_action_ms > 5000) {
            /*
             * 软上限：不清配置，只把每个 cluster 的 scaling_max 往下压一个 OPP。
             * 温度回到软上限 -3℃ 以下时，主循环会重新按档位抬回去。
             * 这样性能曲线在过热边缘是"渐进退让"而不是一刀断电。
             */
            g_fuse.last_soft_action_ms = now;
            for (int i = 0; i < g_fuse.hw->cluster_cnt; ++i) {
                const entropy_cluster *c = &g_fuse.hw->clusters[i];
                int32_t cur;
                if (ent_read_int(c->scaling_max, &cur) != 0) continue;
                for (int j = c->freq_cnt - 1; j >= 0; --j) {
                    if (c->freqs[j] < cur) { ent_write_int(c->scaling_max, c->freqs[j]); break; }
                }
            }
            g_fuse.reason.store(ENT_FUSE_SOFT_LIMIT, std::memory_order_release);
            fuse_log("WARN", "soft limit %" PRId32 "mC, stepped down one OPP", last_temp);
            if (g_fuse.cb) g_fuse.cb(ENT_FUSE_SOFT_LIMIT, last_temp, g_fuse.user);
        }
    }
    return nullptr;
}

}  // namespace

int ent_fuse_start(const entropy_hwmap *hw, const entropy_fuse_cfg *cfg,
                   ent_fuse_callback cb, void *user)
{
    if (!hw || !cfg || g_fuse.started) return -1;
    g_fuse.hw = hw;
    g_fuse.cfg = *cfg;
    if (g_fuse.cfg.fuse_temp_mc <= 0) g_fuse.cfg.fuse_temp_mc = ENT_FUSE_DEFAULT_MC;
    if (g_fuse.cfg.soft_limit_mc <= 0) g_fuse.cfg.soft_limit_mc = ENT_SOFT_DEFAULT_MC;
    /* 这里是最后一线：前三道（App / IPC / 命令行）任一被绕过，线程自己也不越上限 */
    if (g_fuse.cfg.fuse_temp_mc > ENT_FUSE_MAX_MC) g_fuse.cfg.fuse_temp_mc = ENT_FUSE_MAX_MC;
    if (g_fuse.cfg.soft_limit_mc > g_fuse.cfg.fuse_temp_mc)
        g_fuse.cfg.soft_limit_mc = g_fuse.cfg.fuse_temp_mc - 3000;
    if (g_fuse.cfg.poll_interval_ms <= 0) g_fuse.cfg.poll_interval_ms = 1000;
    if (g_fuse.cfg.stall_limit_ms <= 0) g_fuse.cfg.stall_limit_ms = 2000;
    if (g_fuse.cfg.self_cpu_permille_max <= 0)
        g_fuse.cfg.self_cpu_permille_max = ENT_SELF_CPU_BUDGET_PERMILLE;
    g_fuse.cb = cb;
    g_fuse.user = user;
    g_fuse.stop_req.store(0);
    g_fuse.last_kick_ms.store(ent_monotonic_ms());

    pthread_attr_t at;
    pthread_attr_init(&at);
    /* 熔断线程栈给小：它不调用任何递归/格式化-heavy 逻辑 */
    pthread_attr_setstacksize(&at, 64 * 1024);
    int rc = pthread_create(&g_fuse.thread, &at, fuse_thread, nullptr);
    pthread_attr_destroy(&at);
    if (rc != 0) return -1;
    g_fuse.started = true;
    fuse_log("INFO", "fuse armed: fuse=%d soft=%d poll=%dms",
             g_fuse.cfg.fuse_temp_mc, g_fuse.cfg.soft_limit_mc, g_fuse.cfg.poll_interval_ms);
    return 0;
}

void ent_fuse_stop(void)
{
    if (!g_fuse.started) return;
    g_fuse.stop_req.store(1);
    pthread_join(g_fuse.thread, nullptr);
    g_fuse.started = false;
}

void ent_fuse_kick(void)
{
    g_fuse.last_kick_ms.store(ent_monotonic_ms(), std::memory_order_release);
}

void ent_fuse_reset_to_default(const char *why)
{
    do_reset(why ? why : "manual", ENT_FUSE_OK);
    /* 手动还原不进入锁定期，但清掉 reason，让 UI 显示"已还原" */
    g_fuse.locked.store(0, std::memory_order_release);
    g_fuse.reason.store(ENT_FUSE_OK, std::memory_order_release);
}

int ent_fuse_is_locked(void)
{
    return g_fuse.locked.load(std::memory_order_acquire) &&
           ent_monotonic_ms() < g_fuse.lock_until_ms.load(std::memory_order_acquire);
}

entropy_fuse_reason ent_fuse_last_reason(void)
{
    return (entropy_fuse_reason)g_fuse.reason.load(std::memory_order_acquire);
}

int ent_fuse_self_cpu_permille(void)
{
    return g_fuse.self_permille.load(std::memory_order_relaxed);
}
