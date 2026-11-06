/*
 * File: daemon/src/bg_freezer.c
 *
 * 后台"真·暂停"工具（对应需求里的 tombstone_enabler 一项）。
 *
 * 原理：
 *  Linux cgroup freezer 把 cgroup 内所有任务置为 F(FREEZABLE) 状态 ——
 *  它们不再进入调度器的 runnable 队列，也不响应信号（连 SIGKILL 都要等解冻）。
 *  这和 `am kill` 的区别是本质的：kill 会让 app 收到进程死亡回调、下次冷启，
 *  而 freezer 是"时间冻结"，解冻后进程从冻结的那一行继续跑，状态完全保留。
 *  省电/省 CPU 的来源是：彻底消除了后台心跳、JobScheduler 重试、FCM 唤醒带来的
 *  周期性大核点亮。
 *
 * 为什么这不是"欺骗"：
 *  我们没有对系统隐瞒任何传感器数据，只是行使了 Linux 自带的进程组挂起能力，
 *  而且只作用于用户明确点名的第三方包，永远不碰 system_server / surfaceflinger。
 *
 * 唤醒广播屏蔽走官方接口 appops（RUN_ANY_IN_BACKGROUND=ignore），
 * 不去 hook ActivityManager —— 后者一旦实现不稳就会让整机推送失联且难以复原。
 *
 * 用法：
 *   entropy-freezer --freeze com.example.app
 *   entropy-freezer --unfreeze com.example.app
 *   entropy-freezer --status
 */
#include "entropy_ipc.h"
#include "sysfs.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define FREEZE_GROUP "entropy_bg"

/* cgroup v2 与 v1 freezer 两套路径都探测，Android 12~16 大概率是 v2，
 * 但大量 Android 9~11 机型仍是 v1 的 devices/cgroup 挂载。 */
static const char *CGV2_ROOT_CANDS[] = { "/sys/fs/cgroup", "/acct", NULL };
static const char *CGV1_ROOT_CANDS[] = { "/sys/fs/cgroup/freezer", "/dev/freezer", NULL };

static char g_cgroup_dir[256];      /* 实际可用的 freezer 组目录 */
static int  g_is_v2 = 0;

static int probe_cgroup(void)
{
    for (int i = 0; CGV2_ROOT_CANDS[i]; i++) {
        char p[256];
        snprintf(p, sizeof(p), "%s/%s", CGV2_ROOT_CANDS[i], FREEZE_GROUP);
        if (mkdir(p, 0700) == 0 || errno == EEXIST) {
            char t[300];
            snprintf(t, sizeof(t), "%s/cgroup.freeze", p);
            if (access(t, W_OK) == 0) {                 /* 有 freeze 文件才是 v2 unified freezer */
                snprintf(g_cgroup_dir, sizeof(g_cgroup_dir), "%s", p);
                g_is_v2 = 1;
                return 0;
            }
        }
    }
    for (int i = 0; CGV1_ROOT_CANDS[i]; i++) {
        char p[256];
        snprintf(p, sizeof(p), "%s/%s", CGV1_ROOT_CANDS[i], FREEZE_GROUP);
        if (mkdir(p, 0700) == 0 || errno == EEXIST) {
            char t[300];
            snprintf(t, sizeof(t), "%s/state", p);
            if (access(t, W_OK) == 0) {
                snprintf(g_cgroup_dir, sizeof(g_cgroup_dir), "%s", p);
                g_is_v2 = 0;
                return 0;
            }
        }
    }
    return -1;
}

/* 包名字符集校验：只允许 [a-zA-Z0-9._:-]，杜绝把包名当命令用 */
static int pkg_name_ok(const char *pkg)
{
    if (!pkg || !*pkg) return 0;
    size_t n = strlen(pkg);
    if (n > 128) return 0;
    for (size_t i = 0; i < n; i++) {
        char c = pkg[i];
        if (!(isalnum((unsigned char)c) || c == '.' || c == '_' || c == '-' || c == ':')) return 0;
    }
    return 1;
}

/* 关键安全名单：冻结这些进程等于让系统假死 */
static int is_protected(const char *cmd)
{
    static const char *never[] = {
        "system_server", "surfaceflinger", "zygote", "zygote64",
        "/system/bin/", "/vendor/bin/", "init", "ueventd", "logd",
        "entropyd", "entropy-monitor", NULL
    };
    for (int i = 0; never[i]; i++)
        if (strstr(cmd, never[i])) return 1;
    return 0;
}

/*
 * 找出某个包的所有 pid。
 * 一个包可能同时有主进程、:channel、:push 等子进程，
 * 必须全部纳入同一个 freezer 组，否则子进程会把主进程"拽"出冻结状态。
 */
static int collect_pids(const char *pkg, int *pids, int cap)
{
    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *e;
    int n = 0;
    size_t plen = strlen(pkg);
    while ((e = readdir(d)) != NULL && n < cap) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char p[64];
        snprintf(p, sizeof(p), "/proc/%s/cmdline", e->d_name);
        FILE *f = fopen(p, "r");
        if (!f) continue;
        char cmd[256] = {0};
        size_t r = fread(cmd, 1, sizeof(cmd) - 1, f);
        fclose(f);
        if (r == 0) continue;
        /* cmdline 里 argv[0] 后是 NUL；进程改名会把 ":service" 拼在后面 */
        if (strncmp(cmd, pkg, plen) != 0) continue;
        if (cmd[plen] != '\0' && cmd[plen] != ':') continue;    /* 前缀撞名的别的包要排除 */
        if (is_protected(cmd)) continue;
        pids[n++] = atoi(e->d_name);
    }
    closedir(d);
    return n;
}

static long long task_cpu_ms(int pid)
{
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/stat", pid);
    FILE *f = fopen(p, "r");
    if (!f) return -1;
    char line[1024];
    long long ut = 0, st = 0;
    if (fgets(line, sizeof(line), f)) {
        char *q = strrchr(line, ')');
        if (q) {
            int fld = 0;
            char *save = NULL;
            /* strtok_r：strtok 的状态是进程全局的，这个函数一旦被线程或
               信号上下文复用就会读到别的解析进度 */
            for (char *tok = strtok_r(q + 1, " ", &save); tok;
                 tok = strtok_r(NULL, " ", &save)) {
                fld++;
                if (fld == 12) ut = atoll(tok);
                else if (fld == 13) { st = atoll(tok); break; }
            }
        }
    }
    fclose(f);
    long hz = sysconf(_SC_CLK_TCK);
    if (hz <= 0) hz = 100;
    return (ut + st) * 1000 / hz;
}

static int write_node(const char *path, const char *val)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    ssize_t w = write(fd, val, strlen(val));
    int s = errno;
    close(fd);
    if (w < 0) { errno = s; return -1; }
    return 0;
}

static int move_in(int pid)
{
    char p[300], v[32];
    snprintf(p, sizeof(p), "%s/%s", g_cgroup_dir, g_is_v2 ? "cgroup.procs" : "tasks");
    snprintf(v, sizeof(v), "%d", pid);
    return write_node(p, v);
}

static int set_frozen(int freeze)
{
    char p[300];
    if (g_is_v2) {
        snprintf(p, sizeof(p), "%s/cgroup.freeze", g_cgroup_dir);
        return write_node(p, freeze ? "1" : "0");
    }
    snprintf(p, sizeof(p), "%s/state", g_cgroup_dir);
    return write_node(p, freeze ? "FROZEN" : "THAWED");
}

static int do_freeze(const char *pkg)
{
    int pids[256];
    int n = collect_pids(pkg, pids, 256);
    if (n == 0) { fprintf(stderr, "no live process for %s\n", pkg); return -1; }

    long long before = 0;
    for (int i = 0; i < n; i++) before += task_cpu_ms(pids[i]);
    for (int i = 0; i < n; i++)
        if (move_in(pids[i]) != 0) fprintf(stderr, "move pid %d failed: %s\n", pids[i], strerror(errno));
    if (set_frozen(1) != 0) { fprintf(stderr, "freeze failed: %s\n", strerror(errno)); return -1; }

    /*
     * 顺带用官方接口关掉后台启动能力。这是 appops，不是 hook：
     * 系统知道发生了什么，用户在设置里能看见、能撤销 —— 可逆性很重要。
     */
    char cmd[320];
    snprintf(cmd, sizeof(cmd), "cmd appops set '%s' RUN_ANY_IN_BACKGROUND ignore", pkg);
    int rc = system(cmd);
    (void)rc;

    FILE *f = fopen(ENTROPY_LOG_PATH, "a");
    if (f) {
        fprintf(f, "[%lld] FREEZE %s pids=%d cpu_at_freeze=%lldms bgstart_ignore=%d\n",
                (long long)ent_monotonic_ms(), pkg, n, before, rc == 0);
        fclose(f);
    }
    printf("frozen %s: %d threads, cpu=%lldms\n", pkg, n, before);
    return 0;
}

static int do_unfreeze(const char *pkg)
{
    set_frozen(0);
    /* 解冻后把进程搬回根组，否则它们下次还在我们的组里 */
    char p[300];
    snprintf(p, sizeof(p), "%s/%s", g_cgroup_dir, g_is_v2 ? "cgroup.procs" : "tasks");
    int c[32];
    FILE *f = fopen(p, "r");
    int n = 0;
    if (f) { while (n < 32 && fscanf(f, "%d", &c[n]) == 1) n++; fclose(f); }
    char root[300];
    snprintf(root, sizeof(root), "%s", g_is_v2 ? "/sys/fs/cgroup/cgroup.procs" : "/dev/cpuctl/tasks");
    for (int i = 0; i < n; i++) {
        char v[32];
        snprintf(v, sizeof(v), "%d", c[i]);
        write_node(root, v);
    }
    char cmd[320];
    snprintf(cmd, sizeof(cmd), "cmd appops set '%s' RUN_ANY_IN_BACKGROUND allow", pkg);
    int rc = system(cmd);
    (void)rc;
    printf("unfrozen %s (%d pids moved back)\n", pkg, n);
    return 0;
}

static int do_status(void)
{
    char p[300];
    snprintf(p, sizeof(p), "%s/%s", g_cgroup_dir, g_is_v2 ? "cgroup.procs" : "tasks");
    FILE *f = fopen(p, "r");
    if (!f) { printf("freezer group %s not present\n", g_cgroup_dir); return 0; }
    int pid;
    int n = 0;
    long long cpu = 0;
    printf("entropy freezer @ %s (v%d)\n", g_cgroup_dir, g_is_v2 ? 2 : 1);
    while (fscanf(f, "%d", &pid) == 1) {
        char comm[64] = {0};
        char cp[64];
        snprintf(cp, sizeof(cp), "/proc/%d/comm", pid);
        FILE *cf = fopen(cp, "r");
        if (cf) { if (fgets(comm, sizeof(comm), cf)) { char *nl = strchr(comm, '\n'); if (nl) *nl = 0; } fclose(cf); }
        long long ms = task_cpu_ms(pid);
        cpu += ms > 0 ? ms : 0;
        printf("  pid=%-6d %-24s cpu=%lldms\n", pid, comm, ms);
        n++;
    }
    fclose(f);
    printf("frozen tasks=%d accumulated_cpu=%lldms\n", n, cpu);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: entropy-freezer --freeze|--unfreeze <pkg> | --status\n");
        return 2;
    }
    const char *mode = argv[1];
    if (!strcmp(mode, "--status")) {
        if (probe_cgroup() != 0) { printf("no usable freezer cgroup on this kernel\n"); return 0; }
        return do_status();
    }
    if (argc < 3) { fprintf(stderr, "missing package name\n"); return 2; }
    const char *pkg = argv[2];
    if (!pkg_name_ok(pkg)) { fprintf(stderr, "illegal package name\n"); return 2; }

    /* 解冻必须先能找到组，否则进程会被永久冻死 —— 这是唯一真正危险的失败模式 */
    if (probe_cgroup() != 0) {
        fprintf(stderr, "no usable freezer cgroup on this kernel; refusing to freeze\n");
        return 1;
    }
    if (!strcmp(mode, "--freeze"))   return do_freeze(pkg) == 0 ? 0 : 1;
    if (!strcmp(mode, "--unfreeze")) return do_unfreeze(pkg) == 0 ? 0 : 1;
    fprintf(stderr, "unknown mode %s\n", mode);
    return 2;
}
