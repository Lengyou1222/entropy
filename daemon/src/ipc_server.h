/*
 * File: daemon/src/ipc_server.h
 *
 * Unix Socket 服务端。职责边界很清楚：
 *   只做「收发 + 路由」，不做任何策略判断。
 * 所有硬件写操作都转交给 scheduler / limit_detector / safety_fuse，
 * 这样 IPC 线程永远是短临界区，不会成为熔断看门狗眼中的"主循环停更"。
 */
#ifndef ENTROPY_IPC_SERVER_H
#define ENTROPY_IPC_SERVER_H

#include "entropy_ipc.h"
#include "sysfs.h"

typedef struct {
    const entropy_hwmap *hw;
    /* 由 main.cpp 提供的执行器，把协议请求翻译成硬件动作 */
    int  (*on_profile)(const entropy_profile_req *req);
    int  (*on_limit)(const entropy_limit_req *req);
    int  (*on_probe)(const entropy_probe_req *req);
    int  (*on_stop_probe)(void);
    int  (*on_reset)(void);
    int  (*on_freeze)(const char *pkg, int enable);
    int  (*on_monitor_cfg)(const entropy_monitor_cfg *cfg);
    int  (*on_node)(const char *spec);   /* "path=value" */
} entropy_ipc_handlers;

int  ent_ipc_start(const entropy_ipc_handlers *h);
void ent_ipc_stop(void);

/* 事件广播：telemetry / probe_result / thermal_event / heartbeat */
int  ent_ipc_broadcast(uint16_t type, const void *payload, uint32_t len);

/* 共享内存遥测：App mmap 后按 seqlock 读取，避免每次刷新都走一次 syscall 往返 */
int  ent_ipc_shm_init(void);
void ent_ipc_shm_publish(const entropy_telemetry *t);
int  ent_ipc_shm_fd(void);

/*
 * 帧率探针（libentropy_hook.so）的上报接收端。
 * 与 App 通道分开的理由：上报方是被注入的第三方进程，它既不在 entropy.allow
 * 白名单里、也不该有资格发命令。所以这是一条独立的 abstract DGRAM 只入站通道，
 * 只接受 ENT_CMD_HOOK_REPORT 一种帧，且只更新观测数据，不触发任何硬件写动作。
 */
int  ent_ipc_hook_start(void);
void ent_ipc_hook_stop(void);
/* 取最近 1s 内聚合出的帧率数据；没有新鲜数据返回 0（探针视为离线） */
int  ent_ipc_hook_latest(entropy_hook_report *out);

#endif /* ENTROPY_IPC_SERVER_H */
