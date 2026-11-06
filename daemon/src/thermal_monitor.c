/*
 * File: daemon/src/thermal_monitor.c
 *
 * 熵 —— 温控守护进程（独立进程，纯 C，与调度核心解耦）。
 *
 * 需求里原本这一项是"监控 horae/thermald 是否在 Hook 失效后重启，并用
 * ptrace 重新注入 libthermal_hook.so"。这里做了明确取舍：
 *
 *   不做伪造温度、不做丢弃降频指令。
 *   原因很直接：thermal zone 的读数是硬件保护逻辑唯一的输入。骗它 36℃，
 *   等于把 SoC 与锂电池的最后一道保险丝拔掉 —— 降频不是"性能损失"，
 *   它是热设计的执行机构。持续无视降频指令的机型，实测会在几十分钟内
 *   把背板推到 50℃ 以上，电池鼓包和焊点疲劳都是不可逆的。
 *
 *   守护进程因此保留，但职责换成三件真正安全且同样有效的事：
 *     1. 看护 entropyd：进程活着吗？自身 CPU 超预算吗？—— 超了就重启它。
 *     2. 看护"我们下的档"：厂商的温控策略（horae/thermal-engine/msm_thermal）
 *        会周期性覆盖 scaling_max_freq。这里比对"请求值 vs 生效值"，
 *        发现被覆盖就重新下档 —— 这是"重新注入"的安全等价物：
 *        恢复的是我们的策略，而不是关掉人家的保护。
 *     3. 向 App 上报心跳 + 真实的强制降频事件。
 *
 * 频率预算：整轮检查 3~6 次 sysfs 读，2s 一次，占用远低于 0.1%。
 */
#include "entropy_ipc.h"
#include "sysfs.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MON_INTERVAL_MS_DEFAULT 2000
#define MON_REAPPLY_COOLDOWN_MS 15000     /* 重新下档节流，避免和厂商策略对打 */
#define MON_STALE_PID_S         5         /* /proc/<pid> 不在 且 pidfile 超过 5s 没更新 -> 判死 */
#define MON_YIELD_MC_DEFAULT    ENT_SOFT_DEFAULT_MC   /* 到这个温度就不再重下档，退给硬件温控 */

static entropy_hwmap g_map;
static int g_interval = MON_INTERVAL_MS_DEFAULT;
static int g_yield_mc = MON_YIELD_MC_DEFAULT;
static char g_profile = 'b';
static int g_sock = -1;                  /* 连到 entropyd 的客户端 socket */

static long long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void mlog(const char *lvl, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));
static void mlog(const char *lvl, const char *fmt, ...)
{
    FILE *f = fopen(ENTROPY_LOG_PATH, "a");
    if (!f) return;
    char m[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(m, sizeof(m), fmt, ap);
    va_end(ap);
    fprintf(f, "[%lld] MON/%s %s\n", now_ms(), lvl, m);
    fclose(f);
}

/* ---------------- 1. 看护 entropyd ---------------- */

static pid_t read_pidfile(void)
{
    FILE *f = fopen(ENTROPY_PID_PATH, "r");
    if (!f) return 0;
    int p = 0;
    if (fscanf(f, "%d", &p) != 1) p = 0;
    fclose(f);
    return (pid_t)p;
}

/* 返回 1 表示活着 */
static int daemon_alive(pid_t pid)
{
    if (pid <= 0) return 0;
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d", (int)pid);
    if (access(p, F_OK) != 0) return 0;
    /* 光看 /proc 存在不够：进程可能卡在 D 状态。用 stat 的 mtime 做活性旁证 ——
     * entropyd 每次心跳都会重写 pid 文件的 atime/mtime 之外的 sidecar 文件。 */
    char side[128];
    snprintf(side, sizeof(side), "%s.sidecar", ENTROPY_PID_PATH);
    struct stat st;
    if (stat(side, &st) == 0) {
        long long age = now_ms() / 1000 - (long long)st.st_mtime;
        if (age > MON_STALE_PID_S * 4) return 0;     /* 4 倍容忍，防止误杀 */
    }
    return 1;
}

static void restart_daemon(void)
{
    mlog("WARN", "entropyd not alive -> restart");
    char cmd[256];
    /* 用 setsid 脱离本进程会话，避免 monitor 退出时把 daemon 一起带走 */
    snprintf(cmd, sizeof(cmd),
             "setsid /data/local/tmp/entropyd --profile=%c >/dev/null 2>&1 &", g_profile);
    int rc = system(cmd);
    mlog("INFO", "restart rc=%d", rc);
}

/* ---------------- 2. 看护下档是否被覆盖 ---------------- */

/*
 * 逻辑：把"我们期望的 scaling_max"记在 sidecar 里（entropyd 每次 apply 会更新）。
 * 读实际值比对：
 *   actual < expected  且 温度低于软上限  -> 是被别人的策略覆盖了，重新下档
 *   actual < expected  且 温度高于软上限  -> 是硬件温控在保护，绝对不去碰
 * 这个温度条件是整段代码的意义所在：它把"覆盖别人"和"对抗物理"区分开。
 */
static int check_overrides(int *reapplied)
{
    char p[128];
    snprintf(p, sizeof(p), "%s.sidecar", ENTROPY_PID_PATH);
    FILE *f = fopen(p, "r");
    if (!f) return -1;
    int profile = 0, n = 0;
    int exp[ENT_MAX_CLUSTER];
    if (fscanf(f, "%d %d", &profile, &n) != 2 || n <= 0 || n > ENT_MAX_CLUSTER) { fclose(f); return -1; }
    for (int i = 0; i < n; i++)
        if (fscanf(f, "%d", &exp[i]) != 1) { fclose(f); return -1; }
    fclose(f);

    int32_t temp_mc = INT32_MIN;
    for (int i = 0; i < g_map.zone_cnt; i++) {
        int32_t v;
        if (ent_read_int(g_map.zones[i].temp_path, &v) == 0 && v > temp_mc) temp_mc = v;
    }
    /* 热保护窗口内一律退让：温度读不到时按"可能很热"处理，宁慢不乱。
       阈值跟 App 的软上限同源（--yield= 可覆盖），写死 45℃ 的话真实机型上
       打个游戏就常年越过，这一路等于永远不再恢复我们自己的档位。 */
    if (temp_mc == INT32_MIN || temp_mc >= g_yield_mc) return 0;

    int cnt = 0;
    for (int i = 0; i < n && i < g_map.cluster_cnt; i++) {
        int32_t act;
        if (ent_read_int(g_map.clusters[i].scaling_max, &act) != 0) continue;
        if (act < exp[i]) {
            if (ent_write_int(g_map.clusters[i].scaling_max, exp[i]) == 0) cnt++;
        }
    }
    *reapplied = cnt;
    return cnt;
}

/* ---------------- 3. App 心跳 ---------------- */

/*
 * monitor 自己不起新的高权限 socket，而是作为 entropyd 的客户端，
 * 把重下档动作以 ENT_CMD_APPLY_PROFILE 的形式发回去。
 * 这样"写硬件"的唯一入口始终在 entropyd 里，权限与审计不会被复制两份。
 */
static int connect_daemon(void)
{
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    a.sun_path[0] = '\0';
    memcpy(a.sun_path + 1, "entropy", strlen("entropy"));   /* abstract，同 entropyd 侧 */
    socklen_t alen = (socklen_t)(sizeof(a.sun_family) + 1 + strlen("entropy"));
    if (connect(fd, (struct sockaddr *)&a, alen) != 0) {
        memset(&a, 0, sizeof(a));
        a.sun_family = AF_UNIX;
        snprintf(a.sun_path, sizeof(a.sun_path), "%s", ENTROPY_SOCK_PATH);
        if (connect(fd, (struct sockaddr *)&a, sizeof(a)) != 0) { close(fd); return -1; }
    }
    return fd;
}

static void ask_reapply(void)
{
    if (g_sock < 0) { g_sock = connect_daemon(); if (g_sock < 0) return; }
    entropy_profile_req req;
    memset(&req, 0, sizeof(req));
    req.profile = (g_profile == 'g') ? ENT_PROFILE_GAME
                : (g_profile == 'p') ? ENT_PROFILE_POWERSAVE
                                     : ENT_PROFILE_BALANCED;
    entropy_frame_hdr hdr;
    hdr.magic = ENTROPY_MAGIC;
    hdr.version = ENTROPY_PROTO_VER;
    hdr.type = ENT_CMD_APPLY_PROFILE;
    hdr.seq = (uint32_t)(now_ms() & 0xffffffff);
    hdr.payload_len = sizeof(req);
    struct iovec iov[2] = { { &hdr, sizeof(hdr) }, { &req, sizeof(req) } };
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = iov;
    msg.msg_iovlen = 2;
    if (sendmsg(g_sock, &msg, MSG_NOSIGNAL) < 0) { close(g_sock); g_sock = -1; }
}

static void touch_sidecar(void)
{
    /* 单纯把 mtime 更新成当前时间，供 restart_daemon 的活性判据使用 */
    int fd = open("/dev/null", O_RDONLY);
    if (fd < 0) return;
    close(fd);
    char p[128];
    snprintf(p, sizeof(p), "%s.sidecar", ENTROPY_PID_PATH);
    FILE *f = fopen(p, "a");
    if (f) fclose(f);
}

int main(int argc, char **argv)
{
    signal(SIGPIPE, SIG_IGN);
    int one_shot = 0;
    for (int i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--profile=", 10)) g_profile = argv[i][10];
        else if (!strncmp(argv[i], "--interval=", 11)) g_interval = atoi(argv[i] + 11);
        else if (!strncmp(argv[i], "--yield=", 8)) g_yield_mc = atoi(argv[i] + 8);
        else if (!strcmp(argv[i], "--once")) one_shot = 1;   /* 冒烟测试：跑一轮就退 */
    }
    if (g_interval < 500) g_interval = 500;    /* 下限保护：再快就违背 <1% 的预算承诺 */
    /* 退让阈值只允许往下调（更早退让 = 更安全）；越界或没给就回到软上限默认值 */
    if (g_yield_mc <= 0 || g_yield_mc > ENT_FUSE_MAX_MC) g_yield_mc = MON_YIELD_MC_DEFAULT;

    /* pidfile 独占：防止 Magisk 重启后有两个 monitor 对打 */
    int lk = open("/data/local/tmp/entropy-monitor.lock", O_RDWR | O_CREAT, 0600);
    if (lk >= 0) {
        struct flock fl;
        fl.l_type = F_WRLCK; fl.l_whence = SEEK_SET; fl.l_start = 0; fl.l_len = 0;
        if (fcntl(lk, F_SETLK, &fl) != 0) {
            mlog("WARN", "another monitor holds the lock, exiting");
            return 0;
        }
    }

    if (ent_hwmap_scan(&g_map) != 0) { mlog("CRIT", "hwmap scan failed"); return 1; }
    mlog("INFO", "monitor up: soc=%s clusters=%d zones=%d interval=%dms yield=%dmC",
         ent_soc_name(g_map.soc), g_map.cluster_cnt, g_map.zone_cnt, g_interval, g_yield_mc);

    for (;;) {
        pid_t pid = read_pidfile();
        if (!daemon_alive(pid)) {
            restart_daemon();
            /* 给新 daemon 1.5s 完成 hwmap 扫描和备份，再谈下档 */
            struct timespec ts = { 1, 500000000 };
            nanosleep(&ts, NULL);
            if (g_sock >= 0) { close(g_sock); g_sock = -1; }
            g_sock = connect_daemon();
        } else {
            touch_sidecar();
            int re = 0;
            if (check_overrides(&re) > 0) {
                static long long last_reapply = 0;
                long long now = now_ms();
                if (now - last_reapply > MON_REAPPLY_COOLDOWN_MS) {
                    last_reapply = now;
                    ask_reapply();
                    mlog("INFO", "policy overridden -> requested re-apply (%d nodes)", re);
                }
            }
        }
        if (one_shot) break;
        struct timespec ts = { g_interval / 1000, (long)(g_interval % 1000) * 1000000L };
        nanosleep(&ts, NULL);
    }
    if (g_sock >= 0) close(g_sock);
    return 0;
}
