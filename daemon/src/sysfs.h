/*
 * File: daemon/src/sysfs.h
 *
 * 内核接口抽象层。所有对 /sys、/proc 的访问都收口在这里，好处有两点：
 *  1. 调度器/探测引擎不用关心高通、联发科、展锐的节点命名差异；
 *  2. 每一次写入都能被统一记录，安全熔断触发时可以精确还原"是谁把
 *     频率抬到 3.4GHz 的"。
 */
#ifndef ENTROPY_SYSFS_H
#define ENTROPY_SYSFS_H

/*
 * 这个头同时被 C（thermal_monitor.c / bg_freezer.c / sysfs.c）和
 * C++（scheduler.cpp / main.cpp ...）包含。sysfs.c 是按 C 编译的，
 * 没有下面的 extern "C" 会导致 C++ 侧按 mangled 名字找符号而链接失败。
 */
#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

#define ENT_MAX_CLUSTER 12
#define ENT_MAX_ZONE    16
#define ENT_MAX_NODE    256

/* 芯片家族：不同家族的频率/温控节点路径完全不同，先探测再决定用哪套映射表 */
typedef enum {
    ENT_SOC_UNKNOWN = 0,
    ENT_SOC_QCOM,      /* 高通骁龙：/sys/devices/system/cpu/cpufreq/policyN + /sys/class/kgsl */
    ENT_SOC_MTK,       /* 联发科天玑：/proc/mediatek/cpum* + /sys/class/miu* 或 mtkgpufreq */
    ENT_SOC_EXYNOS,    /* 三星猎户座 */
    ENT_SOC_GOOGLE,    /* Pixel Tensor */
    ENT_SOC_OTHER,
} entropy_soc;

/* 一个可调频的 CPU cluster 的完整描述 */
typedef struct {
    int      index;                       /* policy 序号 */
    char     scaling_max[ENT_MAX_NODE];   /* .../cpufreq/policyN/scaling_max_freq */
    char     scaling_min[ENT_MAX_NODE];
    char     available[ENT_MAX_NODE];     /* scaling_available_frequencies，用于枚举 OPP */
    char     governor[ENT_MAX_NODE];
    char     related_cpus[ENT_MAX_NODE];  /* 该 cluster 包含的逻辑核，绑核用 */
    char     urgent_path[ENT_MAX_NODE];   /* MTK 的 WARPITCH/urgent 节点，高通为空 */
    int32_t  freqs[64];                   /* OPP 表，升序 */
    int      freq_cnt;
    int32_t  hw_min, hw_max;              /* 硬件允许的频率区间，任何写入都不得越界 */
    int      core_cnt;
} entropy_cluster;

/* 一个 thermal_zone 的描述 */
typedef struct {
    char    name[ENT_MAX_NODE];   /* 例如 "skill-thm"、"ddr"、"battery" */
    char    temp_path[ENT_MAX_NODE];
    char    trip_path[ENT_MAX_NODE];  /* trip_0_point 所在目录前缀，可能不存在 */
    int32_t last_mc;              /* 最近一次真实读数，INT32_MIN 表示不可读 */
} entropy_zone;

typedef struct {
    entropy_soc      soc;
    char             machine[64];
    char             model[128];
    entropy_cluster  clusters[ENT_MAX_CLUSTER];
    int              cluster_cnt;
    char             gpu_max[ENT_MAX_NODE];
    char             gpu_min[ENT_MAX_NODE];
    char             gpu_avail[ENT_MAX_NODE];
    char             gpu_busy[ENT_MAX_NODE];
    int32_t          gpu_hw_min, gpu_hw_max;
    entropy_zone     zones[ENT_MAX_ZONE];
    int              zone_cnt;
    /* 备份区：写之前保存原始值，reset_to_default() 逐项回写 */
    char             backup_path[ENT_MAX_NODE];
} entropy_hwmap;

/* 探测硬件拓扑；结果写入 map，返回 0 成功 */
int  ent_hwmap_scan(entropy_hwmap *map);
/* 把当前所有已下档节点的原始值存到 backup_path(JSON 行)，用于还原 */
int  ent_backup_save(const entropy_hwmap *map);
int  ent_backup_restore(const entropy_hwmap *map);

/* 读单个 int32 节点；失败返回 -1。带超时保护的 sysfs 读，避免卡死调度线程 */
int  ent_read_int(const char *path, int32_t *out);
int  ent_read_str(const char *path, char *buf, size_t len);
/* 写节点。会对 path 做白名单校验（只允许 /sys、/proc 下已登记的节点），
 * 并把 (path,value,ts) 追加到审计日志。返回 0 成功。 */
int  ent_write_int(const char *path, int32_t value);
/* 带范围钳制的频率写入：value 会被 clamp 到 [hw_min, hw_max]，越界时拒绝 */
int  ent_write_freq_clamped(const char *path, int32_t value, int32_t lo, int32_t hi);

int  ent_property_get(const char *key, char *out, size_t len);
int  ent_property_set(const char *key, const char *value);

/* 读取电池瞬时功率(mW，正=放电)。优先 /sys/class/power_supply，取不到返回 -1 */
int  ent_read_power_mw(void);

/* 单调毫秒时钟，用于探测 dwell 与熔断计时 */
int64_t ent_monotonic_ms(void);

const char *ent_soc_name(entropy_soc soc);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* ENTROPY_SYSFS_H */
