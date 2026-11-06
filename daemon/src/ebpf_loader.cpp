/*
 * File: daemon/src/ebpf_loader.cpp
 *
 * 见 ebpf_loader.h 的双路径设计说明。本文件负责：
 *   路径 A —— dlopen 系统自带的 libbpf（Android 12+ 在 /system/lib64/libbpf.so，
 *             由 netd/tethering 依赖引入），用它 open/load/attach 我们内嵌的 .bpf.o，
 *             然后消费 ring buffer。
 *   路径 B —— libbpf 不存在、或 BTF 缺失、或 verifier 拒绝时，退化到
 *             /proc/[pid]/task/[tid]/schedstat 轮询。schedstat 三元组是
 *             [累计运行ns, 累计等待ns, 时间片数]，第 2 项的一阶差分就是
 *             "这个线程在 runnable 队列里排了多久"，与 eBPF 的 wakeup-latency
 *             语义等价，只是精度从 ns 事件级降为 50ms 采样级。
 */
#include "ebpf_loader.h"
#include "sysfs.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <map>
#include <mutex>
#include <string>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

/* 与 entropy_sched.bpf.c 里的事件结构逐字段镜像。
 * 不能直接 include 那个 .c 的类型：它依赖 bpf 工具链头文件（bpf_helpers.h）。
 * 两边字段顺序/宽度改动必须同步，这也是 shared/protocol 里单独立一份注释的原因。 */
struct ent_switch_event_local {
    uint64_t ts;
    uint32_t tgid, pid, cpu;
    uint64_t prev_state;
    int64_t  delta_ns;
    uint32_t type, nice;
};
struct ent_gpu_event_local {
    uint64_t ts;
    uint32_t tgid, pid;
    uint64_t submit_ns;
    uint32_t q, flags;
};
static_assert(sizeof(ent_switch_event_local) == 48, "must match eBPF side layout");


/* ---------------------------------------------------------------- *
 * libbpf 的最小符号声明。不 include <bpf/libbpf.h>：NDK 里没有它，  *
 * 而且我们只用到 6 个函数，dlopen + 手写原型比拉一个头文件依赖干净。 *
 * ---------------------------------------------------------------- */
namespace {

struct bpf_object;
struct bpf_program;
struct bpf_map;
struct ring_buffer;

typedef struct bpf_object *(*fn_open)(const char *path);
typedef int (*fn_load)(struct bpf_object *);
typedef void (*fn_close)(struct bpf_object *);
typedef struct bpf_program *(*fn_next_prog)(struct bpf_object *, struct bpf_program *);
typedef const char *(*fn_prog_name)(struct bpf_program *);
typedef int (*fn_prog_attach)(struct bpf_program *, int /*bpf_target_fd*/, int type, int flags);
typedef struct bpf_map *(*fn_find_map)(struct bpf_object *, const char *);
typedef int (*fn_map_fd)(struct bpf_map *);
typedef int (*fn_map_update)(int fd, const void *k, const void *v, uint64_t flags);
typedef int (*fn_map_get_next_key)(int fd, const void *k, void *next);
typedef struct ring_buffer *(*fn_ring_new)(int fd, void (*cb)(void *, void *, size_t), void *ctx, uint64_t opts);
typedef int (*fn_ring_poll)(struct ring_buffer *, int timeout_ms);
typedef void (*fn_ring_free)(struct ring_buffer *);

struct LibbpfApi {
    void *h = nullptr;
    fn_open        obj_open;
    fn_load        obj_load;
    fn_close       obj_close;
    fn_next_prog   prog_next;
    fn_prog_name   prog_name;
    fn_prog_attach prog_attach;
    fn_find_map    map_find;
    fn_map_fd      map_fd;
    fn_map_update  map_update;
    fn_ring_new    ring_new;
    fn_ring_poll   ring_poll;
    fn_ring_free   ring_free;
};

LibbpfApi lb;
std::atomic<entropy_signal_source> g_source{ENT_SIGNAL_NONE};
const entropy_hwmap *g_hwmap = nullptr;   /* 由 ent_ebpf_bind_hwmap 注入 */

/* attach 类型常量（与 linux/bpf.h 的 enum bpf_attach_type 对齐） */
constexpr int BPF_TRACE_TRACEPOINT = 5;

struct BpfState {
    struct bpf_object *obj = nullptr;
    struct ring_buffer *rb = nullptr;
    int watched_map_fd = -1;
    int events_map_fd  = -1;

    /* ring buffer 回调累计 */
    std::atomic<uint64_t> n_voluntary{0};
    std::atomic<uint64_t> n_preempt{0};
    std::atomic<int64_t>  last_gpu_submit_ns{0};

    /* 唤醒延迟用 1024 桶直方图，桶宽 0.5ms，避免在用户态排序 */
    static constexpr int HB_BUCKETS = 1024;
    std::mutex hb_mtx;
    uint32_t hb[HB_BUCKETS]{};

    int64_t prev_voluntary = 0, prev_preempt = 0;
};
BpfState bs;

/* 我们只把 obj 放在 tmpfs，因为 libbpf 的 open 需要路径。
 * 第一个是 service.sh 复制过去的位置（和 entropyd 同级，权限已由 root 设好）；
 * 第二个是模块目录里的原件，tmpfs 那份被人删掉时还能读到。
 * 目录名必须与 magisk/module.prop 的 id 一致，改一处就要改两处。 */
const char *BPF_OBJ_PATHS[] = {
    "/data/local/tmp/entropy_sched.bpf.o",
    "/data/adb/modules/entropy/bpf/entropy_sched.bpf.o",
};

bool try_dlopen_libbpf()
{
    /* Android 12+ 的系统 libbpf；部分机型在 /apex 或 /system/lib64 */
    const char *cands[] = { "libbpf.so", "/system/lib64/libbpf.so", "/apex/com.android.tethering/lib64/libbpf.so" };
    for (const char *c : cands) {
        lb.h = dlopen(c, RTLD_NOW | RTLD_LOCAL);
        if (lb.h) break;
    }
    if (!lb.h) return false;
    auto sym = [&](const char *n) { return dlsym(lb.h, n); };
    lb.obj_open    = (fn_open)sym("bpf_object__open_file");
    lb.obj_load    = (fn_load)sym("bpf_object__load");
    lb.obj_close   = (fn_close)sym("bpf_object__close");
    lb.prog_next   = (fn_next_prog)sym("bpf_object__next_program");
    lb.prog_name   = (fn_prog_name)sym("bpf_program__name");
    lb.prog_attach = (fn_prog_attach)sym("bpf_program__attach");
    lb.map_find    = (fn_find_map)sym("bpf_object__find_map_by_name");
    lb.map_fd      = (fn_map_fd)sym("bpf_map__fd");
    lb.map_update  = (fn_map_update)sym("bpf_map_update_elem");
    lb.ring_new    = (fn_ring_new)sym("ring_buffer__new");
    lb.ring_poll   = (fn_ring_poll)sym("ring_buffer__poll");
    lb.ring_free   = (fn_ring_free)sym("ring_buffer__free");
    return lb.obj_open && lb.obj_load && lb.prog_attach && lb.map_find && lb.ring_new;
}

void ring_cb(void *ctx, void *data, size_t len)
{
    (void)ctx;
    if (len >= sizeof(ent_switch_event_local)) {
        auto *e = (ent_switch_event_local *)data;
        if (e->type == 1) bs.n_preempt.fetch_add(1, std::memory_order_relaxed);
        else if (e->type == 0) bs.n_voluntary.fetch_add(1, std::memory_order_relaxed);
        else if (e->type == 3) {
            int idx = (int)(e->delta_ns / 500000);          /* 0.5ms 桶 */
            if (idx < 0) idx = 0;
            if (idx >= BpfState::HB_BUCKETS) idx = BpfState::HB_BUCKETS - 1;
            std::lock_guard<std::mutex> g(bs.hb_mtx);
            bs.hb[idx]++;
        }
    } else if (len >= sizeof(ent_gpu_event_local)) {
        auto *g = (ent_gpu_event_local *)data;
        bs.last_gpu_submit_ns.store((int64_t)g->submit_ns, std::memory_order_relaxed);
    }
}

int load_ebpf_path()
{
    if (!try_dlopen_libbpf()) { errno = ENOENT; return -1; }

    struct bpf_object *obj = nullptr;
    for (const char *p : BPF_OBJ_PATHS) {
        if (access(p, R_OK) != 0) continue;
        obj = lb.obj_open(p);
        if (obj) break;
    }
    if (!obj) { errno = ENOENT; return -1; }
    if (lb.obj_load(obj) != 0) { lb.obj_close(obj); return -1; }
    bs.obj = obj;

    /* attach 所有 program：tracepoint 直接 attach，不需要被追踪对象的 fd */
    for (struct bpf_program *prog = lb.prog_next(obj, nullptr); prog; prog = lb.prog_next(obj, prog)) {
        if (lb.prog_attach(prog, -1, BPF_TRACE_TRACEPOINT, 0) < 0) {
            /* kprobe 类 program 在没有对应符号的内核上必然失败，这不是致命错误：
             * 剩下的 sched_* 探针照样能给出掉帧信号。 */
        }
    }

    struct bpf_map *m = lb.map_find(obj, "watched_tgids");
    if (m) bs.watched_map_fd = lb.map_fd(m);
    m = lb.map_find(obj, "events");
    if (m) {
        bs.events_map_fd = lb.map_fd(m);
        /* 第三个参数是 opts，libbpf 用 0 表示默认（内部 self-pipe 唤醒） */
        bs.rb = lb.ring_new(bs.events_map_fd, ring_cb, nullptr, 0);
    }
    return bs.rb ? 0 : -1;
}

/* ---------------- 路径 B：schedstat 轮询 ---------------- */

struct SchedstatState {
    int32_t tgid = 0;
    std::map<int32_t, std::pair<int64_t, int64_t>> prev;  /* pid -> (run_ns, wait_ns) */
    uint64_t preempt = 0;
    int64_t  wait_delta_max_ns = 0;
    std::vector<int64_t> wait_samples;
};
SchedstatState ss;
std::mutex ss_mtx;

/*
 * 一次遍历 /proc/[tgid]/task/[tid]。
 * 4 万线程的进程不存在，实际游戏进程 40~120 线程，
 * 每线程两次 open/read/close，在 50ms 窗口里耗时 ~1.5ms，CPU 预算完全够。
 */
int sample_schedstat(int64_t window_ms, entropy_load_metrics *out)
{
    std::lock_guard<std::mutex> g(ss_mtx);
    if (ss.tgid <= 0) { memset(out, 0, sizeof(*out)); out->window_ms = window_ms; return 0; }

    char dir[64];
    snprintf(dir, sizeof(dir), "/proc/%d/task", ss.tgid);
    DIR *d = opendir(dir);
    if (!d) { memset(out, 0, sizeof(*out)); return -1; }

    ss.wait_samples.clear();
    ss.preempt = 0;
    int threads = 0;
    dirent *e;
    while ((e = readdir(d)) != nullptr) {
        if (e->d_name[0] == '.') continue;
        int pid = atoi(e->d_name);
        char p[128];
        snprintf(p, sizeof(p), "/proc/%d/task/%d/schedstat", ss.tgid, pid);
        FILE *f = fopen(p, "r");
        if (!f) continue;
        long long run_ns = 0, wait_ns = 0, slices = 0;
        int got = fscanf(f, "%lld %lld %lld", &run_ns, &wait_ns, &slices);
        fclose(f);
        if (got != 3) continue;
        threads++;
        auto it = ss.prev.find(pid);
        if (it != ss.prev.end()) {
            int64_t dw = wait_ns - it->second.second;
            if (dw > 0) ss.wait_samples.push_back(dw);
            /* 时间片数增加但运行时间没跟上 -> 被抢占的强信号 */
            if (dw > 2000000) ss.preempt++;
        }
        ss.prev[pid] = { run_ns, wait_ns };
    }
    closedir(d);

    out->window_ms = window_ms;
    out->target_tgid = ss.tgid;
    out->target_threads = threads;
    out->render_thread_busy = threads > 0 ? 1 : 0;
    out->preempt_count = ss.preempt;
    out->voluntary_switch = 0;    /* schedstat 分不出自愿/非自愿，这里保守报 0 */
    if (!ss.wait_samples.empty()) {
        std::sort(ss.wait_samples.begin(), ss.wait_samples.end());
        size_t i = (ss.wait_samples.size() * 95) / 100;
        out->wakeup_lat_p95_ns = ss.wait_samples[i];
    }
    /* 退化路径拿不到 GPU 提交时刻，用 kgsl 的 busy 节点近似 */
    int32_t busy;
    if (g_hwmap && g_hwmap->gpu_busy[0] && ent_read_int(g_hwmap->gpu_busy, &busy) == 0)
        out->gpu_last_submit_ns = busy > 0 ? ent_monotonic_ms() * 1000000LL : 0;
    return 0;
}

}  // namespace

/* ---------------- 公共 API ---------------- */

void ent_ebpf_bind_hwmap(const entropy_hwmap *hw) { g_hwmap = hw; }

int ent_ebpf_init(bool allow_fallback)
{
    if (load_ebpf_path() == 0) {
        g_source.store(ENT_SIGNAL_EBPF);
        return 0;
    }
    if (allow_fallback) {
        g_source.store(ENT_SIGNAL_SCHEDSTAT);
        return 0;
    }
    g_source.store(ENT_SIGNAL_NONE);
    return -1;
}

void ent_ebpf_shutdown(void)
{
    /* BpfState 含 atomic 与 mutex，不能整体赋值，逐字段复位 */
    if (bs.rb && lb.ring_free) lb.ring_free(bs.rb);
    if (bs.obj && lb.obj_close) lb.obj_close(bs.obj);
    bs.obj = nullptr;
    bs.rb = nullptr;
    bs.watched_map_fd = -1;
    bs.events_map_fd = -1;
    bs.n_voluntary.store(0);
    bs.n_preempt.store(0);
    bs.last_gpu_submit_ns.store(0);
    bs.prev_voluntary = bs.prev_preempt = 0;
    {
        std::lock_guard<std::mutex> g(bs.hb_mtx);
        memset(bs.hb, 0, sizeof(bs.hb));
    }
    g_source.store(ENT_SIGNAL_NONE);
}

int ent_ebpf_watch(int32_t tgid)
{
    ss.tgid = tgid;
    ss.prev.clear();
    if (g_source.load() == ENT_SIGNAL_EBPF && tgid >= 0) {
        /*
         * ARRAY map 的 key 是 tgid & 2047，value 存真实 tgid。
         * 内核里的 is_watched() 做全等比较，所以槽位冲突只是丢掉一个非目标进程，
         * 绝不会误追踪别人。
         */
        uint32_t key = (uint32_t)tgid & 2047u;
        uint32_t val = (uint32_t)tgid;
        if (tgid == 0) {
            /* 取消观察：把该槽清零 */
            uint32_t zero = 0;
            if (lb.map_update) lb.map_update(bs.watched_map_fd, &key, &zero, 0 /*BPF_ANY*/);
            return 0;
        }
        if (lb.map_update) return lb.map_update(bs.watched_map_fd, &key, &val, 0);
    }
    return 0;
}

int ent_ebpf_sample(int64_t window_ms, entropy_load_metrics *out)
{
    memset(out, 0, sizeof(*out));
    out->window_ms = window_ms;
    if (g_source.load() == ENT_SIGNAL_EBPF) {
        /* 非阻塞 poll：timeout 0 表示只消费已经在 ring 里的事件 */
        if (bs.rb && lb.ring_poll) lb.ring_poll(bs.rb, 0);
        out->voluntary_switch  = bs.n_voluntary.load() - bs.prev_voluntary;
        out->preempt_count     = bs.n_preempt.load()  - bs.prev_preempt;
        bs.prev_voluntary = bs.n_voluntary.load();
        bs.prev_preempt   = bs.n_preempt.load();

        /* 直方图取 95 分位 */
        uint32_t total = 0;
        {
            std::lock_guard<std::mutex> g(bs.hb_mtx);
            for (uint32_t v : bs.hb) total += v;
            if (total) {
                uint32_t want = total * 95 / 100, acc = 0;
                for (int i = 0; i < BpfState::HB_BUCKETS; ++i) {
                    acc += bs.hb[i];
                    if (acc >= want) { out->wakeup_lat_p95_ns = (int64_t)(i + 1) * 500000; break; }
                }
            }
            memset(bs.hb, 0, sizeof(bs.hb));
        }
        int64_t now_ns = ent_monotonic_ms() * 1000000LL;
        out->gpu_last_submit_ns = bs.last_gpu_submit_ns.load();
        out->render_thread_busy = out->gpu_last_submit_ns && (now_ns - out->gpu_last_submit_ns < 100000000) ? 1 : 0;
        out->target_tgid = 0;
        return 0;
    }
    if (g_source.load() == ENT_SIGNAL_SCHEDSTAT) return sample_schedstat(window_ms, out);
    return -1;
}

entropy_signal_source ent_ebpf_source(void) { return g_source.load(); }

const char *ent_ebpf_source_name(void)
{
    switch (g_source.load()) {
    case ENT_SIGNAL_EBPF:      return "eBPF (tracepoint + ringbuf)";
    case ENT_SIGNAL_SCHEDSTAT: return "schedstat fallback";
    default:                   return "disabled";
    }
}

int ent_ebpf_dump_object(const char *path)
{
    /* 调试用：把设备上实际加载的 .o 复制出来，便于离线用 `bpftool dump xlated` 反汇编 */
    for (const char *p : BPF_OBJ_PATHS) {
        if (access(p, R_OK) != 0) continue;
        char cmd[512];
        snprintf(cmd, sizeof(cmd), "cp '%s' '%s'", p, path);
        return system(cmd) == 0 ? 0 : -1;
    }
    errno = ENOENT;
    return -1;
}
