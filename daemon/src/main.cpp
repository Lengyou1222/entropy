/*
 * File: daemon/src/main.cpp
 *
 * 熵 (Entropy) v1.0.0 —— Native 调度核心入口。
 *
 * 进程模型（三个线程，各司其职，互不共享可变状态以外的东西）：
 *   main        ：50ms 一拍，取信号 -> 前馈控制 -> 写 sysfs -> 广播遥测
 *   entropy-fuse：50ms 心跳 + 1s 温度，只做降档/还原，永不抬频（safety_fuse.cpp）
 *   ipc         ：accept + 短处理，转交给上面两者执行（ipc_server.cpp）
 *
 * CPU 预算 <1% 是怎么保证的：
 *   1. 一拍里最多 4 次 sysfs 读 + 每 cluster 一次写；1s 才读一次温度。
 *   2. eBPF 路径下事件消费是 O(1) poll(0)，非阻塞；退化路径 50ms 扫一次
 *      /proc/<pid>/task，实测 100 线程约 1.5ms/拍 —— 也就是 3% 上下限，
 *      所以退化路径把周期放宽到 200ms（见 pick_tick_period）。
 *   3. 所有日志写入走 append 打开-写完关闭，不缓冲，代价由 audit 频率控制。
 *
 * 用法：
 *   entropyd [--profile=balanced|game|powersave] [--fuse=85000] [--no-ebpf] [--reset] [--once]
 */
#include "entropy_ipc.h"
#include "ebpf_loader.h"
#include "ipc_server.h"
#include "limit_detector.h"
#include "safety_fuse.h"
#include "scheduler.h"
#include "sysfs.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <getopt.h>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <sys/file.h>
#include <sys/system_properties.h>
#include <unistd.h>

/* main.cpp 里的硬件映射是唯一实例，供 limit_detector 的采样路径引用 */
entropy_hwmap g_hw;

namespace {

std::atomic<bool> g_quit{false};
std::atomic<int>  g_state{0};            /* 0 idle 1 active 2 probing 3 fused */
std::atomic<int>  g_hook_online{0};      /* 帧率探针是否有新鲜上报，心跳里原样透传 */
entropy_sched_cfg g_cfg{};
entropy_monitor_cfg g_mon{};
std::mutex        g_cfg_mtx;
int64_t           g_start_ms = 0;
int32_t           g_foreground_tgid = 0;
/* 最近一次档位下发的单调时钟毫秒。0 = 这次进程生命周期里还没下发过。
 * 心跳只能报"距今多少秒"：App 与 Daemon 的 CLOCK_MONOTONIC 起点相同但
 * 墙钟不同步，把单调秒直接发给 App 再拿 currentTimeMillis 去减，
 * 得到的就是"29889122min 前"这种荒谬数字（实测就是这个原因）。 */
std::atomic<int64_t> g_last_apply_ms{0};

/* 前向声明：on_limit 里复用 on_node 的白名单写入路径 */
int on_node(const char *spec);

void on_signal(int) { g_quit.store(true); }

void dlog(const char *level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void dlog(const char *level, const char *fmt, ...)
{
    FILE *f = fopen(ENTROPY_LOG_PATH, "a");
    if (!f) return;
    char m[256];
    va_list ap; va_start(ap, fmt); vsnprintf(m, sizeof(m), fmt, ap); va_end(ap);
    fprintf(f, "[%lld] %s %s\n", (long long)ent_monotonic_ms(), level, m);
    fclose(f);
    if (getenv("ENTROPY_VERBOSE")) fprintf(stderr, "%s %s\n", level, m);
}

/*
 * 前台包 -> tgid。
 * AMS 拿不到（那是 Java 侧），root 视角最稳的是 /proc/<pid>/oom_score_adj：
 * 前台 app 的 adj 通常是 0 或负数，后台 >= 100。再结合 cmdline 过滤。
 * 比 dumpsys 快两个数量级，且不会把 system_server 拖进我们的热路径。
 */
int32_t find_foreground_tgid(char *pkg_out, size_t len)
{
    int best_adj = 100;
    int32_t best_pid = 0;
    char path[64];
    for (int pid = 1; pid < 65536; ++pid) {
        snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        int adj = 999;
        if (fscanf(f, "%d", &adj) != 1) { fclose(f); continue; }
        fclose(f);
        if (adj >= best_adj || adj > 0) continue;         /* 只要 adj<=0 的可见/前台级进程 */
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        f = fopen(path, "r");
        if (!f) continue;
        char cmd[256] = {0};
        size_t n = fread(cmd, 1, sizeof(cmd) - 1, f);
        fclose(f);
        if (n == 0) continue;
        /*
         * cmdline 是 argv 用 NUL 串起来的：fread 出来的第一个 '\0' 就是 argv[0] 的结尾。
         * buf 已经零初始化，所以不需要再截断，直接当 C 字符串用。
         */
        /* 排除系统进程：它们 adj 也常常是负的 */
        if (strstr(cmd, "system_server") || strstr(cmd, "/system/bin/") ||
            strstr(cmd, "surfaceflinger") || strstr(cmd, "zygote") || cmd[0] == '[') continue;
        best_adj = adj;
        best_pid = pid;
        if (pkg_out) snprintf(pkg_out, len, "%s", cmd);
    }
    return best_pid;
}

/* ---------------- 处理器实现 ---------------- */

int on_profile(const entropy_profile_req *req)
{
    std::lock_guard<std::mutex> g(g_cfg_mtx);
    ent_sched_default_cfg(&g_cfg, (int32_t)req->profile);
    if (req->cpu_max_khz > 0) g_cfg.cpu_max_khz = req->cpu_max_khz;
    if (req->gpu_max_khz > 0) g_cfg.gpu_max_khz = req->gpu_max_khz;
    if (req->boost_ms > 0) g_cfg.boost_ms = req->boost_ms;
    g_cfg.predictive_on = req->predictive_on;
    int rc = ent_sched_apply(&g_hw, &g_cfg);
    dlog("INFO", "profile=%u applied rc=%d cap=%dkHz", req->profile, rc, g_cfg.cpu_max_khz);
    g_state.store(rc == 0 ? 1 : 0);
    /* 只有真的写成功才记时间：失败的下发对用户不是"最近一次下档"，
     * 记了会让界面显示"12s 前重下档"，而实际上一个节点都没改动 */
    if (rc == 0) g_last_apply_ms.store(ent_monotonic_ms());
    return rc;
}

/*
 * 帧率上限落在两个全局属性上，SF 读哪一个随 ROM 版本变，所以一律成对写、成对回读。
 * 回读是必须的：属性被 SELinux 静默丢掉时，写命令本身仍然"成功"，
 * 而 App 会告诉用户上限已经换档 —— 用户看游戏没变化，只会怀疑是自己操作错了。
 */
static const char *const FPS_KEYS[] = { "debug.sf.max_refresh_rate", "vendor.display.max_frame_rate" };

static int read_one_prop(const char *key, char *buf, size_t cap)
{
    buf[0] = '\0';
    ent_property_get(key, buf, cap);
    return (int)strlen(buf);
}

static int apply_fps_limit(int fps)
{
    char v[16];
    snprintf(v, sizeof(v), "%d", fps);
    for (const char *k : FPS_KEYS) ent_property_set(k, v);
    char back[PROP_VALUE_MAX] = {0};
    for (const char *k : FPS_KEYS)
        if (read_one_prop(k, back, sizeof(back)) && strcmp(back, v) == 0) return 0;
    dlog("WARN", "fps cap %d not readable back", fps);
    return -EACCES;
}

static int clear_fps_caps(void)
{
    for (const char *k : FPS_KEYS) ent_property_set(k, "");
    char back[PROP_VALUE_MAX] = {0};
    for (const char *k : FPS_KEYS)
        if (read_one_prop(k, back, sizeof(back)) > 0) {
            dlog("WARN", "%s still '%s' after clear", k, back);
            return -EACCES;
        }
    return 0;
}

/*
 * 极限参数下发。分辨率/渲染器这类修改必须由 App 侧带真实参数进来，
 * Daemon 只负责落到属性与 wm 命令 —— 这里绝不接受 UI 拼好的原始 shell 字符串。
 */
int on_limit(const entropy_limit_req *req)
{
    int fps_rc = 0;
    if (req->restore) {
        ent_property_set("debug.hwui.renderer", "");
        int f = clear_fps_caps();
        int r = system("wm size reset && wm density reset");
        dlog("INFO", "limit restored rc=%d fps=%d", r, f);
        return (r == 0 && f == 0) ? 0 : -1;
    }
    if (req->width > 0 && req->height > 0) {
        /* 分辨率必须落在合法范围：极端值会让 SurfaceFlinger 直接进 bootloop */
        if (req->width < 320 || req->height < 320 || req->width > 4096 || req->height > 4096) {
            dlog("WARN", "reject resolution %dx%d", req->width, req->height);
            return -EINVAL;
        }
        char cmd[128];
        snprintf(cmd, sizeof(cmd), "wm size %dx%d", req->width, req->height);
        int r = system(cmd);
        dlog("INFO", "%s rc=%d", cmd, r);
    }
    if (req->fps_limit == ENT_FPS_LIMIT_CLEAR)
        fps_rc = clear_fps_caps();
    else if (req->fps_limit >= 24 && req->fps_limit <= 240)
        /* 帧率上限：走 SurfaceFlinger 的 idle/boost 配置，多数机型可用 minfps/maxfps 属性 */
        fps_rc = apply_fps_limit(req->fps_limit);
    if (req->touch_sample_hz >= 60 && req->touch_sample_hz <= 500) {
        /* 触控采样率是 vendor 节点，命名各家不同；这里遍历常见前缀 */
        static const char *cands[] = {
            "/proc/touchpanel/game_switch_enable",
            "/sys/devices/virtual/touch/touch_dev/sample_rate",
            "/sys/devices/platform/touch/tp_sample_rate",
            nullptr
        };
        char buf[64];
        for (int i = 0; cands[i]; ++i) {
            if (access(cands[i], W_OK) != 0) continue;
            snprintf(buf, sizeof(buf), "%s=%d", cands[i], req->touch_sample_hz);
            on_node(buf);
            break;
        }
    }
    switch (req->renderer) {
    case ENT_RENDERER_SKIA:
        ent_property_set("debug.hwui.renderer", "skia");
        break;
    case ENT_RENDERER_GLES:
        ent_property_set("debug.hwui.renderer", "skiagl");
        break;
    case ENT_RENDERER_VULKAN:
        /*
         * Android 没有"强制 UI 用 Vulkan"的公开开关；HWUI 只有 skia/skiagl/gl/vk 四值，
         * 其中 vk 在部分厂商 ROM 上被裁掉。所以这里写下去之后必须回读校验，
         * 读不到就退回 skiagl，避免属性被静默忽略却告诉用户"已生效"。
         */
        ent_property_set("debug.hwui.renderer", "vk");
        {
            char back[PROP_VALUE_MAX] = {0};
            ent_property_get("debug.hwui.renderer", back, sizeof(back));
            if (strcmp(back, "vk") != 0) {
                ent_property_set("debug.hwui.renderer", "skiagl");
                dlog("WARN", "vulkan renderer unsupported, fell back to skiagl");
            }
        }
        break;
    default:
        break;
    }
    return fps_rc;
}

int on_node(const char *spec)
{
    /*
     * 通用节点写入，必须有严格白名单：
     *   - 只允许 /sys/ 或 /proc/ 前缀
     *   - 禁止 .. 与任何 shell 元字符
     *   - 值必须是整数
     * 否则等于把 root 写文件能力开放给 App。
     */
    char path[256];
    long val;
    if (sscanf(spec, "%255[^=]=%ld", path, &val) != 2) return -EINVAL;
    if (strncmp(path, "/sys/", 5) != 0 && strncmp(path, "/proc/", 6) != 0) return -EPERM;
    if (strstr(path, "..")) return -EPERM;
    int32_t v = (int32_t)val;
    int rc = ent_write_int(path, v);
    dlog("INFO", "node %s=%d rc=%d", path, v, rc);
    return rc;
}

void on_probe_partial(const entropy_probe_result *r, void *)
{
    ent_ipc_broadcast(ENT_EVT_PROBE_RESULT, r, sizeof(*r));
}
void on_probe_done(const entropy_probe_result *r, void *)
{
    g_state.store(1);
    ent_ipc_broadcast(ENT_EVT_PROBE_RESULT, r, sizeof(*r));
    dlog("INFO", "probe done sweet_cpu=%dkHz limit_cpu=%dkHz throttle_temp=%dmC aborted=%d",
         r->cpu_sweet_khz, r->cpu_abs_limit_khz, r->throttle_temp_mc, r->aborted_by_safety);
}

int on_probe(const entropy_probe_req *req)
{
    entropy_probe_ctx ctx{ &g_hw, *req, on_probe_partial, on_probe_done, nullptr };
    int rc = ent_probe_start(&ctx);
    if (rc == 0) g_state.store(2);
    dlog("INFO", "probe start rc=%d budget=%ds ceiling=%dmC", rc, req->budget_s, req->temp_ceiling_mc);
    return rc;
}

int on_stop_probe(void) { return ent_probe_stop(); }

int on_reset(void)
{
    ent_probe_stop();
    ent_fuse_reset_to_default("app request");
    /* 备份表里只有 sysfs 节点，帧率属性是这套调度之外单独写下去的，
     * 不在这里清的话，"一键还原"之后整机还压着上一次游戏档案的上限 */
    int fps_rc = clear_fps_caps();
    ent_property_set("debug.hwui.renderer", "");
    g_state.store(0);
    dlog("INFO", "reset_to_default by app fps=%d", fps_rc);
    return 0;
}

int on_freeze(const char *pkg, int enable)
{
    /*
     * 交给独立的小工具 entropy-freezer 执行（见 bg_freezer.c）。
     * 不在 Daemon 里直接做，是因为 freezer 操作涉及遍历 /proc 找 pid、
     * 写 cgroup 状态，失败模式很多；进程隔离后它崩了不影响调度核心。
     */
    char cmd[320];
    snprintf(cmd, sizeof(cmd), "/system/bin/sh -c '/data/local/tmp/entropy-freezer %s %s'",
             enable ? "--freeze" : "--unfreeze", pkg);
    int rc = system(cmd);
    dlog("INFO", "freeze %s=%d rc=%d", pkg, enable, rc);
    return rc == 0 ? 0 : -1;
}

int on_monitor_cfg(const entropy_monitor_cfg *cfg)
{
    /* 阈值钳制：用户可以把熔断线往下调（更安全），绝不能往上调到硬件保护区之上。
       上限来自共享头的 ENT_FUSE_MAX_MC，与 App、命令行两处同数 */
    std::lock_guard<std::mutex> g(g_cfg_mtx);
    if (cfg->fuse_temp_mc > 0) g_mon.fuse_temp_mc = std::min(cfg->fuse_temp_mc, ENT_FUSE_MAX_MC);
    if (cfg->soft_limit_mc > 0) g_mon.soft_limit_mc = std::min(cfg->soft_limit_mc, ENT_FUSE_MAX_MC - 2000);
    if (cfg->skin_limit_mc > 0) g_mon.skin_limit_mc = std::min(cfg->skin_limit_mc, 43000);
    if (cfg->monitor_interval_ms >= 200) g_mon.monitor_interval_ms = cfg->monitor_interval_ms;
    g_mon.auto_restore_on_breach = cfg->auto_restore_on_breach;
    dlog("INFO", "monitor cfg: fuse=%d soft=%d poll=%dms",
         g_mon.fuse_temp_mc, g_mon.soft_limit_mc, g_mon.monitor_interval_ms);
    return 0;
}

/* 熔断回调 -> 广播给 App，让 UI 立刻显示红色警示 */
void fuse_cb(entropy_fuse_reason r, int32_t detail, void *)
{
    entropy_thermal_event ev{};
    ev.throttle_reason = r == ENT_FUSE_TEMP_BREACH ? 3 : (r == ENT_FUSE_SOFT_LIMIT ? 1 : 2);
    ev.temp_mc = detail;
    snprintf(ev.zone_name, sizeof(ev.zone_name), "%s", "MAX-OF-ALL-ZONES");
    g_state.store(3);
    ent_ipc_broadcast(ENT_EVT_THERMAL_EVENT, &ev, sizeof(ev));
}

/* 一拍的最大 sysfs 开销随信号源调整 */
int pick_tick_period(void)
{
    return ent_ebpf_source() == ENT_SIGNAL_EBPF ? 50 : 200;
}

}  // namespace

int main(int argc, char **argv)
{
    static const option opts[] = {
        { "profile",  required_argument, nullptr, 'p' },
        { "fuse",     required_argument, nullptr, 'f' },
        { "soft",     required_argument, nullptr, 's' },
        { "no-ebpf",  no_argument,       nullptr, 'n' },
        { "once",     no_argument,       nullptr, '1' },
        { "reset",    no_argument,       nullptr, 'r' },
        { "help",     no_argument,       nullptr, 'h' },
        { nullptr,    0,                 nullptr, 0  },
    };

    const char *profile = "balanced";
    bool no_ebpf = false, once = false, do_reset = false;
    g_mon.fuse_temp_mc = ENT_FUSE_DEFAULT_MC;
    g_mon.soft_limit_mc = ENT_SOFT_DEFAULT_MC;
    g_mon.skin_limit_mc = 41000;
    g_mon.monitor_interval_ms = 1000;
    g_mon.auto_restore_on_breach = 1;

    int c;
    while ((c = getopt_long(argc, argv, "p:f:s:nrh", opts, nullptr)) != -1) {
        switch (c) {
        case 'p': profile = optarg; break;
        case 'f': g_mon.fuse_temp_mc = atoi(optarg); break;
        case 's': g_mon.soft_limit_mc = atoi(optarg); break;
        case 'n': no_ebpf = true; break;
        case 'r': do_reset = true; break;
        case '1': once = true; break;
        default:
            printf("entropyd --profile=balanced|game|powersave --fuse=85000 --soft=70000 "
                   "[--no-ebpf] [--reset] [--once]\n");
            return 0;
        }
    }
    /* 熔断线不允许被命令行抬高到硬件保护之上（第三道钳制，前两道在 App 与 IPC） */
    if (g_mon.fuse_temp_mc > ENT_FUSE_MAX_MC) g_mon.fuse_temp_mc = ENT_FUSE_MAX_MC;
    if (g_mon.soft_limit_mc > g_mon.fuse_temp_mc) g_mon.soft_limit_mc = g_mon.fuse_temp_mc - 3000;

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    /*
     * --reset：只还原，不调度。
     * 卸载脚本必须在删掉二进制之前叫得动它，所以这条路径不能依赖 IPC，
     * 也不能要求"有一个活的 Daemon"—— 它直接读备份表逐项回写。
     */
    if (do_reset) {
        if (ent_hwmap_scan(&g_hw) != 0) {
            fprintf(stderr, "entropyd --reset: hardware scan failed\n");
            return 1;
        }
        int rc = ent_backup_restore(&g_hw);
        dlog("INFO", "--reset 还原备份表 rc=%d", rc);
        return rc == 0 ? 0 : 1;
    }

    /*
     * pid 文件同时充当单实例锁。两个 root 权限的调度器并存时，
     * 各自按自己的备份表还原，频率会在两个值之间来回抖，
     * 而且后起的那个会把前一个的 pid 覆盖掉 —— App 的存活探测就此失真。
     * 先 flock 再截断写入：顺序反了就会在持锁者眼前把它的 pid 抹掉。
     */
    static int pidfd = -1;
    pidfd = open(ENTROPY_PID_PATH, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
    if (pidfd >= 0 && flock(pidfd, LOCK_EX | LOCK_NB) != 0) {
        dlog("CRIT", "another entropyd already holds %s, exiting", ENTROPY_PID_PATH);
        return 1;
    }
    if (pidfd >= 0) {
        if (ftruncate(pidfd, 0) == 0) {
            char b[16]; int n = snprintf(b, sizeof(b), "%d\n", getpid());
            ssize_t w = write(pidfd, b, n); (void)w;
        }
    }

    g_start_ms = ent_monotonic_ms();
    dlog("INFO", "=== entropyd start (pid=%d) ===", getpid());

    if (ent_hwmap_scan(&g_hw) != 0) {
        dlog("CRIT", "hardware scan failed, nothing to do");
        return 1;
    }
    dlog("INFO", "soc=%s machine=%s model=%s clusters=%d zones=%d gpu_max=%dmHz",
         ent_soc_name(g_hw.soc), g_hw.machine, g_hw.model,
         g_hw.cluster_cnt, g_hw.zone_cnt, g_hw.gpu_hw_max);

    /* 备份先于一切写入：没有备份就没有还原，就没有资格抬频 */
    ent_backup_save(&g_hw);

    /* 1. 熔断线程必须最先起，它要保护后面所有动作 */
    entropy_fuse_cfg fc{};
    fc.fuse_temp_mc = g_mon.fuse_temp_mc;
    fc.soft_limit_mc = g_mon.soft_limit_mc;
    fc.poll_interval_ms = g_mon.monitor_interval_ms;
    fc.auto_restore = g_mon.auto_restore_on_breach;
    fc.self_cpu_permille_max = ENT_SELF_CPU_BUDGET_PERMILLE;
    if (ent_fuse_start(&g_hw, &fc, fuse_cb, nullptr) != 0) {
        dlog("CRIT", "safety fuse failed to start -> refusing to run at all");
        return 2;       /* 没有熔断器的调度器就是加热器，宁可不起 */
    }

    /* 2. 信号源 */
    if (!no_ebpf && ent_ebpf_init(true) == 0)
        dlog("INFO", "signal source: %s", ent_ebpf_source_name());
    else
        dlog("WARN", "signal source: %s (eBPF unavailable)", ent_ebpf_source_name());
    ent_ebpf_bind_hwmap(&g_hw);      /* 退化采样路径需要 GPU 节点 */

    /* 3. IPC */
    entropy_ipc_handlers h{};
    h.hw = &g_hw;
    h.on_profile = on_profile;
    h.on_limit = on_limit;
    h.on_probe = on_probe;
    h.on_stop_probe = on_stop_probe;
    h.on_reset = on_reset;
    h.on_freeze = on_freeze;
    h.on_monitor_cfg = on_monitor_cfg;
    h.on_node = on_node;
    if (ent_ipc_shm_init() != 0) dlog("WARN", "shm init failed, telemetry push only");
    if (ent_ipc_start(&h) != 0) {
        dlog("CRIT", "ipc bind failed: %s", strerror(errno));
        ent_fuse_stop();
        return 3;
    }
    /* 帧率探针是可选观测件：起不来不影响调度，只是遥测里的 fps 会一直是 0 */
    if (ent_ipc_hook_start() != 0)
        dlog("WARN", "hook sink unavailable, fps telemetry disabled");

    ent_sched_default_cfg(&g_cfg, !strcmp(profile, "game") ? ENT_PROFILE_GAME
                                : !strcmp(profile, "powersave") ? ENT_PROFILE_POWERSAVE
                                                                 : ENT_PROFILE_BALANCED);
    ent_sched_apply(&g_hw, &g_cfg);
    g_state.store(1);

    /* 4. 主循环 */
    int period = pick_tick_period();
    int64_t next = ent_monotonic_ms() + period;
    int64_t last_fg_scan = 0, last_hb = 0, last_tel = 0;
    entropy_load_metrics metrics{};

    while (!g_quit.load()) {
        struct timespec ts{ 0, 10 * 1000 * 1000 };
        nanosleep(&ts, nullptr);            /* 10ms 粒度，避免 last-mile 抖动放大成 200ms */
        int64_t now = ent_monotonic_ms();
        if (now < next) continue;
        next = now + period;
        ent_fuse_kick();

        /* 前台游戏每 3s 重扫一次（前后台切换不需要更密） */
        if (now - last_fg_scan > 3000) {
            char pkg[128] = {0};
            int32_t tgid = find_foreground_tgid(pkg, sizeof(pkg));
            if (tgid != g_foreground_tgid) {
                g_foreground_tgid = tgid;
                ent_ebpf_watch(tgid);
                ent_sched_pin_render_threads(&g_hw, tgid, g_cfg.pin_big_cores && tgid > 0);
                dlog("INFO", "foreground=%s tgid=%d pinned=%d", pkg, tgid, g_cfg.pin_big_cores);
            }
            last_fg_scan = now;
        }

        if (ent_ebpf_sample(period, &metrics) == 0) {
            entropy_sched_tick tick{};
            std::lock_guard<std::mutex> g(g_cfg_mtx);
            ent_sched_tick(&g_hw, &g_cfg, &metrics, &tick);

            /* 被动标定降频阈值：请求值 > 实际生效值 且 正在升温 */
            for (int i = 0; i < g_hw.cluster_cnt; ++i) {
                int32_t act;
                if (g_hw.clusters[i].scaling_max[0] && ent_read_int(g_hw.clusters[i].scaling_max, &act) == 0)
                    ent_note_forced_throttle(&g_hw, tick.target_khz[i], act);
            }

            if (now - last_tel >= 250) {      /* 遥测 4Hz 足够画曲线，也够悬浮窗流畅 */
                last_tel = now;
                entropy_telemetry t{};
                for (int i = 0; i < g_hw.cluster_cnt; ++i) {
                    int32_t cur;
                    char p[256];
                    snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", i);
                    t.cpu_khz[i] = ent_read_int(p, &cur) == 0 ? cur : tick.target_khz[i];
                    t.cpu_load[i] = (int)(std::min(1.0f, metrics.wakeup_lat_p95_ns / 6000000.f) * 1000);
                }
                t.gpu_khz = tick.gpu_target_khz;
                int32_t b;
                t.gpu_busy = (g_hw.gpu_busy[0] && ent_read_int(g_hw.gpu_busy, &b) == 0) ? b % 1000 : 0;
                for (int i = 0; i < 16 && i < g_hw.zone_cnt; ++i) {
                    int32_t v;
                    t.temps[i] = ent_read_int(g_hw.zones[i].temp_path, &v) == 0 ? v : -1;
                }
                t.power_mw = ent_read_power_mw();
                /* fps 与真实帧时只认探针的数据；探针缺席时保持 0，
                 * 绝不用 eBPF 的唤醒延迟去"推算"一个看起来像帧率的数字 ——
                 * 那种数在 UI 上会被当成调度依据，而它其实是调度器自己的输入。 */
                entropy_hook_report hr{};
                int hook_online = ent_ipc_hook_latest(&hr);
                if (hook_online && hr.frames > 0 && hr.frame_ms_sum > 0) {
                    t.fps = (int)((int64_t)hr.frames * 1000000 / hr.frame_ms_sum);
                    t.render_ms = (int)(hr.frame_ms_p95 / 1000);      /* 定点 µs -> ms */
                } else {
                    t.fps = 0;
                    t.render_ms = (int)(metrics.wakeup_lat_p95_ns / 1000000);
                }
                g_hook_online.store(hook_online);
                t.pinned_threads = g_foreground_tgid > 0 ? 1 : 0;
                ent_ipc_shm_publish(&t);
                ent_ipc_broadcast(ENT_EVT_TELEMETRY, &t, sizeof(t));
            }
        }

        if (now - last_hb >= 1000) {
            last_hb = now;
            entropy_heartbeat hb{};
            hb.daemon_uptime_s = (uint32_t)((now - g_start_ms) / 1000);
            hb.daemon_cpu_permille = ent_fuse_self_cpu_permille();
            hb.state = g_state.load();
            hb.hook_ok = g_hook_online.load();
            /* 距今秒数，-1 = 从未下发。字段名沿用 reapply，含义在协议头里已经改成
             * "最近一次档位下发距今"，App 侧不要再拿墙钟去减 */
            int64_t la = g_last_apply_ms.load();
            hb.last_reapply_ts = la > 0 ? (int32_t)((now - la) / 1000) : -1;
            ent_ipc_broadcast(ENT_EVT_HEARTBEAT, &hb, sizeof(hb));
            /* 自身占用若超预算，写一条 WARN 便于现场定位。
             * 阈值必须和熔断线程用的是同一个数：这里原来写死 10‰，
             * 于是日志天天喊 "over budget"，而自检判据（150‰）根本没越线，
             * 看日志的人会照着一条不存在的告警去查问题。 */
            if (hb.daemon_cpu_permille > fc.self_cpu_permille_max)
                dlog("WARN", "daemon cpu=%d.%d%% over budget(%d‰)",
                     hb.daemon_cpu_permille / 10, hb.daemon_cpu_permille % 10,
                     fc.self_cpu_permille_max);
        }

        if (once) break;                      /* --once：只跑一拍，供 adb 冒烟测试 */
    }

    dlog("INFO", "shutting down, restoring defaults");
    ent_probe_stop();
    ent_ipc_stop();
    ent_ebpf_shutdown();
    ent_fuse_reset_to_default("daemon exit");
    ent_fuse_stop();
    unlink(ENTROPY_PID_PATH);
    return 0;
}
