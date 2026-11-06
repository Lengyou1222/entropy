/*
 * File: shared/protocol/entropy_ipc.h
 *
 * 熵 (Entropy) —— App 与 Native Daemon 共用的 IPC 协议定义。
 *
 * 设计原理：
 *  1. 传输层用 Unix Domain Socket (AF_UNIX, SOCK_STREAM)。相比 Binder/AIDL，
 *     UDS 不需要 Java 层参与，Daemon 可以完全脱离 Zygote 运行在 init 的
 *     服务上下文里，这是"底层调度严禁 Java 处理"这条约束的唯一可行做法。
 *     同时 UDS 走内核 unix 域，不经过网络栈，单帧延迟在 20~60us 量级。
 *  2. 应用层用「固定长度头 + 变长负载」的帧格式，避免在 C 侧做 JSON 解析：
 *     头 16 字节自然对齐，payload 是 4 字节对齐的结构体或 NUL 结尾字符串。
 *  3. 所有频率/温度统一用 int32，温度单位是毫摄氏度(m°C)，与内核
 *     thermal sysfs 的原始读数一致，避免任何一侧出现浮点截断歧义。
 */
#ifndef ENTROPY_IPC_H
#define ENTROPY_IPC_H

#include <stdint.h>

/* ---------- 传输层常量 ---------- */

/*
 * Socket 路径选择 /data/local/tmp 而不是 /dev/socket：
 * /dev/socket 需要写 init.rc 且 SELinux 域受限更严；/data/local/tmp 在
 * Magisk 模块里用 restorecon 打上 shell_data_file 标签后，init 域与
 * 应用侧(通过 su)都能访问，兼容性最好。
 */
#define ENTROPY_SOCK_PATH   "/data/local/tmp/entropy.sock"
#define ENTROPY_PID_PATH    "/data/local/tmp/entropy.pid"
#define ENTROPY_LOG_PATH    "/data/local/tmp/entropy.log"
#define ENTROPY_STATE_PATH  "/data/local/tmp/entropy.state"

/*
 * 帧率探针（LD_PRELOAD hook）上报用的 abstract dgram socket。
 * 用 abstract + SOCK_DGRAM 的原因：
 *  - 被注入的 app 进程没有权限在 /data/local/tmp 建文件（SELinux 直接拒）；
 *  - dgram 不需要 accept 队列，一次 sendto 就是一次上报，hook 侧永远不会阻塞；
 *  - 上报是纯观测数据，丢了就丢下一帧，不值得为它维护连接状态。
 */
#define ENTROPY_HOOK_SOCK_NAME "entropy.hook"

/* 共享内存区：Daemon 写入实时遥测，App 直接 mmap 只读，避免高频请求-响应开销 */
#define ENTROPY_SHM_NAME    "/entropy_telemetry"
#define ENTROPY_SHM_SIZE    4096

#define ENTROPY_MAGIC       0x454E5431u  /* "ENT1" */
#define ENTROPY_PROTO_VER   1u

/* ---------- 帧头 ---------- */
typedef struct {
    uint32_t magic;      /* ENTROPY_MAGIC，用于拒绝任何非本协议的连接 */
    uint16_t version;    /* 协议版本，向前兼容检查 */
    uint16_t type;       /* entropy_msg_type */
    uint32_t seq;        /* 请求序号，响应原样回传，用于乱序匹配 */
    uint32_t payload_len;
} entropy_frame_hdr;     /* 16 bytes, 无 padding */

/* ---------- 消息类型 ---------- */
typedef enum {
    /* App -> Daemon */
    ENT_CMD_PING            = 0x0001,
    ENT_CMD_APPLY_PROFILE   = 0x0002,  /* payload: entropy_profile_req */
    ENT_CMD_SET_NODE        = 0x0003,  /* payload: NUL 结尾 "path=value" */
    ENT_CMD_SET_LIMIT       = 0x0004,  /* payload: entropy_limit_req (分辨率/帧率/采样率/渲染器) */
    ENT_CMD_START_PROBE     = 0x0010,  /* payload: entropy_probe_req —— 启动硬件极限探测 */
    ENT_CMD_STOP_PROBE      = 0x0011,
    ENT_CMD_RESET_DEFAULT   = 0x0012,  /* 全量还原，安全熔断也会走这条路径 */
    ENT_CMD_FREEZE_PKG      = 0x0020,  /* payload: 包名字符串，后台真·暂停 */
    ENT_CMD_UNFREEZE_PKG    = 0x0021,
    ENT_CMD_SET_MONITOR     = 0x0030,  /* payload: entropy_monitor_cfg —— 温控/守护策略 */

    /*
     * Hook -> Daemon（注意：不是 App -> Daemon）。
     * 走的是 ENTROPY_HOOK_SOCK_NAME 那条 dgram 通道，帧头里的 seq 字段被复用为
     * 上报进程自己的 pid，Daemon 侧按 (uid,pid) 聚合。
     */
    ENT_CMD_HOOK_REPORT     = 0x0040,  /* payload: entropy_hook_report */

    /* Daemon -> App */
    ENT_REP_ACK             = 0x8000,  /* payload: entropy_ack */
    ENT_EVT_TELEMETRY       = 0x8100,  /* payload: entropy_telemetry */
    ENT_EVT_PROBE_RESULT    = 0x8101,  /* payload: entropy_probe_result */
    ENT_EVT_THERMAL_EVENT   = 0x8102,  /* payload: entropy_thermal_event —— 真实降频/熔断事件 */
    ENT_EVT_HEARTBEAT       = 0x8103,  /* payload: entropy_heartbeat */
} entropy_msg_type;

/* ---------- 负载结构 ---------- */

/* 调度档位：与 App 的"日常均衡/游戏极速/极致省电"一一对应 */
typedef enum {
    ENT_PROFILE_BALANCED = 0,
    ENT_PROFILE_GAME     = 1,
    ENT_PROFILE_POWERSAVE= 2,
    ENT_PROFILE_CUSTOM   = 3,
} entropy_profile;

typedef struct {
    uint32_t profile;        /* entropy_profile */
    int32_t  cpu_max_khz;    /* CPU 大核最高频率上限，0 = 用探测到的甜点频率 */
    int32_t  gpu_max_khz;    /* GPU 频率上限，0 = 用默认 */
    int32_t  boost_ms;       /* 触控/渲染 boost 时长 */
    int32_t  predictive_on;  /* 是否启用预测性调频 */
} entropy_profile_req;

/*
 * 极限参数。分辨率/帧率/采样率都通过 sysfs 或 wm 命令落地，
 * renderer 对应 debug.hwui.renderer / debug.vulkan.layer 之类属性。
 */
typedef enum {
    ENT_RENDERER_DEFAULT = 0,
    ENT_RENDERER_SKIA    = 1,
    ENT_RENDERER_GLES    = 2,   /* OpenGL ES 3.2 -> skiagl */
    ENT_RENDERER_VULKAN  = 3,   /* Vulkan 1.3   -> 需要厂商支持，失败会回退 */
} entropy_renderer;

/*
 * fps_limit 的哨兵值：只清掉帧率上限，其它极限参数一项都不碰。
 * 用负数而不是给结构体加字段，是因为两端按固定 24 字节打包，
 * 加字段的 payload 会让旧 Daemon 从错误的偏移读出分辨率/渲染器。
 * 也不能复用 restore=1：那条分支连 wm size/density 一起 reset，
 * 用户只是想取消锁帧，不该顺手把缩放好的分辨率也清掉。
 */
#define ENT_FPS_LIMIT_CLEAR (-1)

/*
 * 熔断线允许的最高值(m°C)。App、Daemon、命令行三处各钳一次，三处必须同数，
 * 所以数字放在两侧共享的头里，与 Kotlin 侧 Proto.FUSE_MAX_MC 同源。
 *
 * 为什么不是 50℃：熔断看的是所有热区里的最大值，真实机型上那通常是 SoC / GPU 结温，
 * 负载起来 60~80℃ 是常态。50℃ 于是天天撞线，等于把"最后一道闸"变成了"开机就锁死"。
 * 95℃ 已经贴近内核自己降频的位置；越过这条线熵只做一件事 —— 把调度还原成系统默认。
 */
#define ENT_FUSE_MAX_MC      95000
#define ENT_FUSE_DEFAULT_MC  85000
#define ENT_SOFT_DEFAULT_MC  70000

typedef struct {
    int32_t  width;          /* 0 = 不修改 */
    int32_t  height;
    int32_t  touch_sample_hz;
    int32_t  fps_limit;      /* 24..240 写入；ENT_FPS_LIMIT_CLEAR 清空；其它值不动 */
    int32_t  renderer;       /* entropy_renderer */
    int32_t  restore;        /* 非 0 表示一键还原 */
} entropy_limit_req;

/* 探测请求：probe 会短时抬频，因此必须带硬性时间/温度预算 */
typedef struct {
    uint32_t target;         /* bit0=CPU bit1=GPU */
    int32_t  start_khz;
    int32_t  step_khz;
    int32_t  max_khz;
    int32_t  dwell_ms;       /* 每档停留时间 */
    int32_t  budget_s;       /* 整轮探测最大时长，超时强制停止 */
    int32_t  temp_ceiling_mc;/* 探测期间允许的最高温度(m°C)，超过立即中止 */
} entropy_probe_req;

/* 探测结果：甜点频率 = 每毫焦耳能效比最高的档；极限频率 = 触发熔断前的最后稳定档 */
typedef struct {
    uint32_t done;           /* 探测完成度百分比 */
    int32_t  cpu_sweet_khz;
    int32_t  cpu_abs_limit_khz;
    int32_t  gpu_sweet_khz;
    int32_t  gpu_abs_limit_khz;
    int32_t  throttle_temp_mc;  /* 实测触发强制降频的温度阈值 */
    int32_t  best_perf_per_watt; /* 定点数：perf/W * 1000 */
    int32_t  aborted_by_safety;  /* 非 0 = 因安全熔断中止 */
    char     note[64];
} entropy_probe_result;

typedef struct {
    int32_t cpu_khz[12];     /* 每 cluster 当前频率 */
    int32_t cpu_load[12];    /* 每 cluster 归一化负载 0~1000 */
    int32_t gpu_khz;
    int32_t gpu_busy;        /* 0~1000 */
    int32_t temps[16];       /* thermal_zone 原始读数，-1 = 不可读 */
    int32_t power_mw;        /* 电池功率(绝对值)，取不到则 0 */
    int32_t fps;
    int32_t render_ms;       /* 最近帧的 GPU 时间 */
    int32_t pinned_threads;  /* 被绑核/优先提升的线程数 */
    int32_t frozen_apps;     /* 处于 freezer 状态的后台应用数 */
} entropy_telemetry;

/*
 * 温控策略。注意：这里只有「真实阈值」，没有任何伪造读数的字段。
 * Daemon 做的是在厂商允许的范围内调整冷却设备(cdev)的触发点，
 * 而不是骗过热保护逻辑说"现在只有 36℃"。
 */
typedef struct {
    int32_t fuse_temp_mc;        /* 绝对熔断温度，默认 50000 (50℃) */
    int32_t soft_limit_mc;       /* 软上限，超过则调度器主动降档 */
    int32_t skin_limit_mc;       /* 表面温度告警阈值 */
    int32_t monitor_interval_ms; /* thermal_monitor 轮询周期 */
    int32_t auto_restore_on_breach; /* 熔断后是否自动 reset_to_default */
} entropy_monitor_cfg;

typedef struct {
    int32_t zone;
    int32_t temp_mc;         /* 真实温度 */
    int32_t throttle_reason; /* 0=none 1=soft 2=hard 3=fuse */
    char    zone_name[32];
} entropy_thermal_event;

typedef struct {
    uint32_t daemon_uptime_s;
    int32_t  daemon_cpu_permille;  /* 守护进程自身占用率 * 1000，约束要求 < 1000(1%) */
    int32_t  state;                /* 0=idle 1=active 2=probing 3=fused */
    int32_t  hook_ok;              /* 帧率探针 libentropy_hook.so 在线位：0 = 没有有效上报 */
    /* 最近一次档位下发"距今多少秒"，-1 = 这次进程生命周期里还没下发过。
     * 报的是差值而不是时间戳：Daemon 用 CLOCK_MONOTONIC，App 用墙钟，
     * 两端各减各的才会出现"29889122min 前"这种数（实测踩过）。 */
    int32_t  last_reapply_ts;
} entropy_heartbeat;

/*
 * LD_PRELOAD 帧率探针的一次窗口统计。
 * 时间基准统一用 CLOCK_MONOTONIC 的纳秒（与 eBPF 侧一致），
 * 所有派生量（fps / p95 帧时）都由 Daemon 侧算，hook 只上报原始计数：
 * 被注入进程是别人的地址空间，往里塞浮点运算和策略逻辑等于埋雷。
 */
typedef struct {
    uint32_t pid;                /* 上报进程 pid */
    uint32_t tgid;
    int64_t  window_start_ns;
    int64_t  window_end_ns;
    uint32_t frames;             /* 窗口内 eglSwapBuffers 次数 */
    uint32_t jank_big;           /* 帧时 > 前 3 帧均值 2 倍且 > 32ms 的"大卡顿" */
    uint32_t jank_small;
    int64_t  frame_ms_sum;       /* 定点：帧时 * 1000 累加，用于算平均 */
    int64_t  frame_ms_p50;       /* 定点 *1000 */
    int64_t  frame_ms_p95;       /* 定点 *1000 */
    int32_t  swap_interval;      /* 当前 eglSwapInterval 值，-1 = 未知 */
    uint32_t app_name_hash;      /* 进程名的 FNV-1a，Daemon 用来区分多个前台 app */
} entropy_hook_report;

typedef struct {
    int32_t  code;   /* 0=OK，负数=errno */
    char     msg[60];
} entropy_ack;

#endif /* ENTROPY_IPC_H */
