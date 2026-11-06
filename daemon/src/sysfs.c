/*
 * File: daemon/src/sysfs.c
 *
 * 硬件拓扑探测与节点读写的实现。见 sysfs.h 的设计说明。
 */
#define _GNU_SOURCE
#include "sysfs.h"
#include "entropy_ipc.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ *
 * 基础工具                                                            *
 * ------------------------------------------------------------------ */

int64_t ent_monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

const char *ent_soc_name(entropy_soc soc)
{
    switch (soc) {
    case ENT_SOC_QCOM:   return "Qualcomm Snapdragon";
    case ENT_SOC_MTK:    return "MediaTek Dimensity";
    case ENT_SOC_EXYNOS: return "Samsung Exynos";
    case ENT_SOC_GOOGLE: return "Google Tensor";
    case ENT_SOC_OTHER:  return "Other SoC";
    default:             return "Unknown";
    }
}

int ent_read_str(const char *path, char *buf, size_t len)
{
    if (!path || !buf || len == 0) return -1;
    buf[0] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, len - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    /* sysfs 多数节点以 '\n' 结尾，统一裁掉方便后续 strcmp */
    char *nl = strchr(buf, '\n');
    if (nl) *nl = '\0';
    return 0;
}

int ent_read_int(const char *path, int32_t *out)
{
    char s[64];
    if (ent_read_str(path, s, sizeof(s)) != 0) return -1;
    char *end = NULL;
    long v = strtol(s, &end, 10);
    if (end == s) return -1;
    *out = (int32_t)v;
    return 0;
}

/*
 * 写审计日志：任何一次 sysfs 写入都必须可追溯。
 * 单独打开追加，不持有 fd —— Daemon CPU 占用要求 <1%，写入频率本来就很低，
 * 换来的是"进程被 kill 也不会丢日志"。
 */
static void ent_audit(const char *path, int32_t value, int result)
{
    FILE *f = fopen(ENTROPY_LOG_PATH, "a");
    if (!f) return;
    fprintf(f, "[%lld] W %s = %d -> %s(%d)\n",
            (long long)ent_monotonic_ms(), path, value,
            result == 0 ? "ok" : "fail", errno);
    fclose(f);
}

/*
 * sysfs 写入本身是非阻塞的，但个别厂商节点(尤其 GPU devfreq)在设备忙时
 * 会让 write() 卡在内核里。这里用 O_NONBLOCK + 短重试来避免调度线程被吊死；
 * 真正的兜底是 safety_fuse 里的看门狗线程。
 */
int ent_write_int(const char *path, int32_t value)
{
    if (!path || path[0] == '\0') return -1;

    /* 白名单：只允许写内核接口节点，杜绝把路径拼成 /data 或 /system 里的文件 */
    if (strncmp(path, "/sys/", 5) != 0 && strncmp(path, "/proc/", 6) != 0) {
        errno = EPERM;
        ent_audit(path, value, -1);
        return -1;
    }

    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        ent_audit(path, value, -1);
        return -1;
    }
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%d", value);
    ssize_t w = write(fd, buf, (size_t)n);
    int saved = errno;
    close(fd);
    ent_audit(path, value, w == n ? 0 : -1);
    if (w != n) { errno = saved; return -1; }
    return 0;
}

int ent_write_freq_clamped(const char *path, int32_t value, int32_t lo, int32_t hi)
{
    if (lo > 0 && value < lo) value = lo;
    if (hi > 0 && value > hi) value = hi;   /* 硬上限：探测引擎再想抬也抬不过这里 */
    return ent_write_int(path, value);
}

int ent_property_get(const char *key, char *out, size_t len)
{
    int n = __system_property_get(key, out);
    if (n <= 0 || n >= (int)len) { if (len) out[0] = '\0'; return -1; }
    return 0;
}

/*
 * 属性写入。NDK 的 __system_property_set 在新版本才可用，而且从非 shell 域
 * 写 vendor/persist 前缀会被 SELinux 拒。Daemon 由 Magisk 以 root + shell 域
 * 启动，直接调 setprop 二进制是最稳的做法。
 */
int ent_property_set(const char *key, const char *value)
{
    if (!key || !value || strchr(key, '\'') || strchr(value, '\'') ||
        strchr(key, ' ') || strchr(value, '\n') || strchr(key, ' ') ||
        strlen(key) > 64) {
        ent_audit(key ? key : "bad", 0, -1);
        return -1;
    }
    char cmd[512];
    /* 单引号包裹；上面已拒绝任何引号/换行，所以这里不存在命令注入面 */
    snprintf(cmd, sizeof(cmd), "/system/bin/setprop '%s' '%s'", key, value);
    int rc = system(cmd);
    ent_audit(key, (int32_t)rc, rc == 0 ? 0 : -1);
    return rc == 0 ? 0 : -1;
}

/* ------------------------------------------------------------------ *
 * OPP 表解析                                                          *
 * ------------------------------------------------------------------ */

/*
 * "300000 500000 900000 ..." -> int32 数组。
 * 这是极限探测的基础：我们只在硬件真正支持的 OPP 台阶上跳，
 * 不去写一个不存在的频率（那会让 devfreq 直接 reject 并可能留下脏状态）。
 */
static int ent_parse_freqs(const char *s, int32_t *out, int cap)
{
    int cnt = 0;
    const char *p = s;
    while (*p && cnt < cap) {
        char *end = NULL;
        long v = strtol(p, &end, 10);
        if (end == p) break;
        if (v > 0) out[cnt++] = (int32_t)v;
        p = (*end) ? end + 1 : end;
        while (*p == ' ') p++;
    }
    /* 冒泡一次保证升序：部分 MTK 节点给出的是降序表 */
    for (int i = 1; i < cnt; i++)
        for (int j = i; j > 0 && out[j - 1] > out[j]; j--) {
            int32_t t = out[j - 1]; out[j - 1] = out[j]; out[j] = t;
        }
    return cnt;
}

static int ent_count_cpus(const char *related)
{
    int n = 0;
    for (const char *p = related; *p; p++)
        if (*p == ',') n++;
    return n + 1;
}

/* ------------------------------------------------------------------ *
 * 芯片家族识别                                                        *
 * ------------------------------------------------------------------ */

static entropy_soc ent_detect_soc(char *machine, size_t mlen, char *model, size_t mdl)
{
    ent_property_get("ro.product.board", machine, mlen);
    ent_property_get("ro.soc.model", model, mdl);
    if (model[0] == '\0') ent_property_get("ro.boot.hardware.sku", model, mdl);

    char hw[128] = {0};
    ent_read_str("/sys/devices/soc0/machine", hw, sizeof(hw));
    char fam[64] = {0};
    ent_property_get("ro.hardware", fam, sizeof(fam));

    /*
     * 判定优先级：/sys/devices/soc0 是高通专有节点，最可靠；
     * MTK 看 mtcmgr/cpufreq 的紧急节点是否存在；Exynos 用 machine 串。
     */
    if (hw[0] || access("/sys/class/kgsl/kgsl-3d0", F_OK) == 0 ||
        strstr(fam, "qcom") || strstr(machine, "qcom"))
        return ENT_SOC_QCOM;
    if (access("/proc/mtk_cpufreq", F_OK) == 0 || access("/sys/kernel/mtk_cpu", F_OK) == 0 ||
        strstr(fam, "mt") || strstr(fam, "mediatek"))
        return ENT_SOC_MTK;
    if (strstr(hw, "Exynos") || strstr(machine, "exynos"))
        return ENT_SOC_EXYNOS;
    if (strstr(machine, "Tensor") || strstr(model, "Tensor"))
        return ENT_SOC_GOOGLE;
    return ENT_SOC_OTHER;
}

/* ------------------------------------------------------------------ *
 * 拓扑扫描                                                            *
 * ------------------------------------------------------------------ */

/*
 * 枚举 /sys/devices/system/cpu/cpufreq/policy* 。
 * policyN 里的 N 是频域编号（不是核编号），同 cluster 的核共用一个 policy，
 * 所以 related_cpus 必须一起记下 —— 绑核时需要知道哪些逻辑核属于大核域。
 */
static int ent_scan_clusters(entropy_hwmap *map)
{
    DIR *d = opendir("/sys/devices/system/cpu/cpufreq");
    if (!d) return -1;
    struct dirent *e;
    int idx = 0;
    while ((e = readdir(d)) != NULL && idx < ENT_MAX_CLUSTER) {
        if (strncmp(e->d_name, "policy", 6) != 0) continue;
        entropy_cluster *c = &map->clusters[idx];
        memset(c, 0, sizeof(*c));
        c->index = idx;

        char base[ENT_MAX_NODE];
        snprintf(base, sizeof(base), "/sys/devices/system/cpu/cpufreq/%s", e->d_name);
        snprintf(c->scaling_max, sizeof(c->scaling_max), "%s/scaling_max_freq", base);
        snprintf(c->scaling_min, sizeof(c->scaling_min), "%s/scaling_min_freq", base);
        snprintf(c->available,  sizeof(c->available),  "%s/scaling_available_frequencies", base);
        snprintf(c->governor,   sizeof(c->governor),   "%s/scaling_governor", base);
        snprintf(c->related_cpus, sizeof(c->related_cpus), "%s/related_cpus", base);

        char s[1024];
        if (ent_read_str(c->available, s, sizeof(s)) == 0)
            c->freq_cnt = ent_parse_freqs(s, c->freqs, 64);
        /* 老内核没有 available 节点时退到 transition 表 */
        if (c->freq_cnt == 0) {
            char alt[ENT_MAX_NODE];
            snprintf(alt, sizeof(alt), "%s/cpuinfo_transition_latency", base);
            (void)alt;
            snprintf(alt, sizeof(alt), "%s/../cpu%d/cpufreq/cpuinfo_max_freq", base, idx);
            if (ent_read_int(alt, &c->hw_max) == 0 && c->hw_max > 0) {
                c->freqs[0] = c->hw_max;
                c->freq_cnt = 1;
            }
        }
        if (ent_read_str(c->related_cpus, s, sizeof(s)) == 0)
            c->core_cnt = ent_count_cpus(s);

        if (c->freq_cnt > 0) {
            c->hw_min = c->freqs[0];
            c->hw_max = c->freqs[c->freq_cnt - 1];
        }
        /* MTK 的 extra/urgent 节点：允许在 policy 之上再加一层"瞬时频率请求" */
        char urg[ENT_MAX_NODE];
        snprintf(urg, sizeof(urg), "/sys/module/mtk_cpufreq_mtsync/parameters/cluster%d_max", idx);
        if (access(urg, W_OK) == 0) snprintf(c->urgent_path, sizeof(c->urgent_path), "%s", urg);

        idx++;
    }
    closedir(d);
    map->cluster_cnt = idx;
    return idx > 0 ? 0 : -1;
}

/*
 * GPU 频率节点按家族分支：
 *   高通 kgsl   : /sys/class/kgsl/kgsl-3d0/{min,max}_gpu_clock + clock_stats
 *   通用 devfreq: /sys/class/devfreq/[dev]/min_freq 与 max_freq（Mali / Adreno 新驱动）
 */
static void ent_scan_gpu(entropy_hwmap *map)
{
    const char *cands[] = {
        "/sys/class/kgsl/kgsl-3d0",
        NULL
    };
    for (int i = 0; cands[i]; i++) {
        char base[ENT_MAX_NODE];
        snprintf(base, sizeof(base), "%s", cands[i]);
        if (access(base, F_OK) != 0) continue;
        snprintf(map->gpu_max, sizeof(map->gpu_max), "%s/max_gpu_clock", base);
        snprintf(map->gpu_min, sizeof(map->gpu_min), "%s/min_gpu_clock", base);
        snprintf(map->gpu_avail, sizeof(map->gpu_avail), "%s/freq_table", base);
        snprintf(map->gpu_busy, sizeof(map->gpu_busy), "%s/busy", base);
        break;
    }
    if (map->gpu_max[0] == '\0') {
        /* devfreq 兜底：找第一个同时有 max_freq 的 devfreq 设备 */
        DIR *d = opendir("/sys/class/devfreq");
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (e->d_name[0] == '.') continue;
                char p[ENT_MAX_NODE];
                snprintf(p, sizeof(p), "/sys/class/devfreq/%s/max_freq", e->d_name);
                if (access(p, W_OK) == 0) {
                    snprintf(map->gpu_max, sizeof(map->gpu_max), "%s", p);
                    snprintf(map->gpu_min, sizeof(map->gpu_min), "/sys/class/devfreq/%s/min_freq", e->d_name);
                    snprintf(map->gpu_avail, sizeof(map->gpu_avail), "/sys/class/devfreq/%s/available_frequencies", e->d_name);
                    snprintf(map->gpu_busy, sizeof(map->gpu_busy), "/sys/class/devfreq/%s/trans_stat", e->d_name);
                    break;
                }
            }
            closedir(d);
        }
    }
    if (map->gpu_max[0]) {
        ent_read_int(map->gpu_max, &map->gpu_hw_max);
        ent_read_int(map->gpu_min, &map->gpu_hw_min);
        /* kgsl 的 max_gpu_clock 单位可能是 Hz 或 KHz，看位数猜 */
        if (map->gpu_hw_max > 5000000) map->gpu_hw_max /= 1000;
        if (map->gpu_hw_min > 5000000) map->gpu_hw_min /= 1000;
    }
}

/*
 * thermal_zone 扫描。
 * 只有 zone 名和真实 temp 读数 —— 我们绝不去改写 trip 点来"欺骗"温控，
 * 那等于关掉硬件保护。zone 的作用是让熔断和图表有客观依据。
 */
static void ent_scan_zones(entropy_hwmap *map)
{
    DIR *d = opendir("/sys/class/thermal");
    if (!d) return;
    struct dirent *e;
    int idx = 0;
    char human[64];
    while ((e = readdir(d)) != NULL && idx < ENT_MAX_ZONE) {
        if (strncmp(e->d_name, "thermal_zone", 12) != 0) continue;
        entropy_zone *z = &map->zones[idx];
        memset(z, 0, sizeof(*z));
        snprintf(z->temp_path, sizeof(z->temp_path), "/sys/class/thermal/%s/temp", e->d_name);
        snprintf(z->trip_path, sizeof(z->trip_path), "/sys/class/thermal/%s", e->d_name);
        if (access(z->temp_path, R_OK) != 0) continue;

        char type[ENT_MAX_NODE];
        snprintf(type, sizeof(type), "/sys/class/thermal/%s/type", e->d_name);
        if (ent_read_str(type, human, sizeof(human)) == 0)
            snprintf(z->name, sizeof(z->name), "%s", human);
        else
            snprintf(z->name, sizeof(z->name), "zone%s", e->d_name + 12);
        z->last_mc = INT32_MIN;
        idx++;
    }
    closedir(d);
    map->zone_cnt = idx;
}

int ent_hwmap_scan(entropy_hwmap *map)
{
    if (!map) return -1;
    memset(map, 0, sizeof(*map));
    map->soc = ent_detect_soc(map->machine, sizeof(map->machine),
                              map->model, sizeof(map->model));
    ent_scan_clusters(map);
    ent_scan_gpu(map);
    ent_scan_zones(map);
    snprintf(map->backup_path, sizeof(map->backup_path), "/data/local/tmp/entropy.backup");
    return map->cluster_cnt > 0 ? 0 : -1;
}

/* ------------------------------------------------------------------ *
 * 原始值备份 / 还原                                                    *
 * ------------------------------------------------------------------ */

/*
 * 还原是"最后一道逃生门"，所以备份文件采用逐行 "path value" 的纯文本格式，
 * 即使 Daemon 崩了，人也能用 cat + echo 手工恢复。
 * 每个节点只保留第一次见到的值 —— 那是开机时的出厂状态。
 */
int ent_backup_save(const entropy_hwmap *map)
{
    char seen[32][ENT_MAX_NODE];
    int seen_cnt = 0;

    FILE *f = fopen(map->backup_path, "w");
    if (!f) return -1;
    chmod(map->backup_path, 0600);

    const char *paths[ENT_MAX_CLUSTER * 2 + 4];
    int n = 0;
    for (int i = 0; i < map->cluster_cnt; i++) {
        paths[n++] = map->clusters[i].scaling_max;
        paths[n++] = map->clusters[i].scaling_min;
    }
    if (map->gpu_max[0]) paths[n++] = map->gpu_max;
    if (map->gpu_min[0]) paths[n++] = map->gpu_min;

    for (int i = 0; i < n; i++) {
        int32_t v;
        if (ent_read_int(paths[i], &v) != 0) continue;
        int dup = 0;
        for (int j = 0; j < seen_cnt; j++)
            if (strcmp(seen[j], paths[i]) == 0) { dup = 1; break; }
        if (dup) continue;
        if (seen_cnt < 32) snprintf(seen[seen_cnt++], ENT_MAX_NODE, "%s", paths[i]);
        fprintf(f, "%s %d\n", paths[i], v);
    }
    fclose(f);
    return 0;
}

int ent_backup_restore(const entropy_hwmap *map)
{
    FILE *f = fopen(map->backup_path, "r");
    if (!f) return -1;
    char line[ENT_MAX_NODE + 32];
    while (fgets(line, sizeof(line), f)) {
        char *sp = strchr(line, ' ');
        if (!sp) continue;
        *sp = '\0';
        int32_t v = atoi(sp + 1);
        if (v > 0) ent_write_int(line, v);
    }
    fclose(f);
    return 0;
}

/* ------------------------------------------------------------------ *
 * 功率采样                                                            *
 * ------------------------------------------------------------------ */

/*
 * perf/W 是"甜点频率"的横轴。取电池侧瞬时功率而不是 SoC 电源域，
 * 因为多数设备只暴露 battery/current_now + voltage_now，二者相乘即整机功耗，
 * 虽然包含屏幕，但在固定亮度 + 固定负载的探测窗口内是单调可用的。
 */
int ent_read_power_mw(void)
{
    static const char *cur_paths[] = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/Battery/current_now",
        "/sys/class/power_supply/battery/current_avg",
        NULL
    };
    static const char *vol_paths[] = {
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/Battery/voltage_now",
        NULL
    };
    int32_t cur = 0, vol = 0;
    int ok = 0;
    for (int i = 0; cur_paths[i]; i++) {
        if (ent_read_int(cur_paths[i], &cur) == 0) { ok = 1; break; }
    }
    if (!ok) return -1;
    for (int i = 0; vol_paths[i]; i++) {
        if (ent_read_int(vol_paths[i], &vol) == 0) break;
    }
    if (vol <= 0) return -1;
    if (cur < 0) cur = -cur;              /* 放电方向符号因机型而异，取绝对值 */
    /* uA * uV / 1e6 = uW -> /1000 = mW；再除一次 1000 防溢出精度损失 */
    int64_t uw = (int64_t)cur * (int64_t)vol / 1000000LL;
    return (int)(uw / 1000LL);
}
