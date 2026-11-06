/*
 * File: daemon/src/native_hook.c
 *
 * 熵 (Entropy) —— LD_PRELOAD 帧节奏探针，产物 libentropy_hook.so。
 *
 * 它测什么：一次 eglSwapBuffers 真正返回的时间间隔，也就是「这个 app 实际
 * 每隔多久往屏幕上放了一帧」。调度器要的就是这个数，因为它是唯一能区分
 * "app 想跑 120 帧但只画到 80"和"屏幕本来就只刷 80"的证据。
 *
 * 它不做什么，这一半比那一半重要：
 *   第一，不伪造 thermal_zone 读数 —— 降频是热设计的执行机构，骗过它就等于
 *   同时摘掉 SoC 和锂电池唯一的过热保护（见 thermal_monitor.c 头部说明）。
 *   第二，不丢弃厂商的降频 write —— 同上。
 *   第三，不改任何硬件状态。整个 .so 只读时钟、只发定长 UDS 报文。
 *   所以它被卸载、被 kill、进程崩溃，都不会给设备留下任何需要还原的状态。
 *
 * 注入方式（为什么不是 ptrace）：
 *   Android 的 Zygote 在 specialize 阶段会读 `wrap.<进程名>` 属性，把它的值
 *   拼进该进程的执行环境。因此启用探针是一条命令的事：
 *       setprop wrap.com.example.game 'LD_PRELOAD=/data/local/tmp/libentropy_hook.so '
 *   目标进程下次启动生效。相比 ptrace 注入，这条路：
 *     - 不在已运行进程里改内存，不会触发 APK 完整性/反外挂检测的误报；
 *     - 卸载 = 删属性 + 重启 app，不留痕迹；
 *     - 属性是 per-process 的，天然不会波及 system_server。
 *   ptrace 注入的代价是"hook 实现一旦有 bug，目标进程直接 SIGSEGV"，
 *   对一个性能测量工具来说，让被测对象崩掉是最糟糕的失败模式。
 *
 * 热路径约束（120Hz 下每秒走 120 次）：
 *   - 一次 clock_gettime(CLOCK_MONOTONIC)（vDSO，约 20ns）
 *   - 若干个原子 store；无 malloc、无锁、无 syscall（上报每 ~400ms 才一次）
 *   - 拿不到自旋锁就丢这一轮统计，绝不阻塞渲染线程
 */

#define _GNU_SOURCE 1

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "entropy_ipc.h"

/* ------------------------------------------------------------------ */
/* EGL 类型自持声明                                                     */
/*                                                                      */
/* 不 include <EGL/egl.h>：那个头会把 libEGL 变成 .so 的链接依赖，        */
/* 而探针必须对 libEGL 零依赖（目标进程自己已经加载了它，我们只借符号）。   */
/* EGLBoolean 在 Khronos 定义里就是 uint8_t，签名必须逐字节一致，          */
/* 否则 armv7 上调用方读到的返回值会被截错。                              */
/* ------------------------------------------------------------------ */
typedef uint8_t  ent_eglbool;
typedef int32_t  ent_eglint;

#define ENT_EGL_TRUE 1u

static inline int64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000ll + ts.tv_nsec;
}

/* ------------------------------------------------------------------ */
/* 运行期状态                                                          */
/* ------------------------------------------------------------------ */

#define RING_CAP          256u             /* 环形样本数，必须是 2 的幂 */
#define RING_MASK         (RING_CAP - 1u)
#define REPORT_MIN_NS     400000000ll      /* 上报窗口下限 400ms */
#define RETRY_MIN_NS      30000000000ll    /* 连不上时 30s 才重试，绝不刷屏 */

typedef ent_eglbool (*swap_fn)(void *, void *);
typedef ent_eglbool (*swapi_fn)(void *, ent_eglint);

static _Atomic swap_fn  real_swap;     /* 真实现。未绑定时走转发兜底路径 */
static _Atomic swapi_fn real_swapi;

/* 探针总开关。构造期结束后不再变化（除兜底绑定），热路径只多一次 relaxed load。 */
static int g_enabled = 0;
/* 构造期标志：loader 锁还没放开时绝不能 dlopen。 */
static int g_in_ctor = 0;

static int      g_sock = -1;
static uint32_t g_pid = 0, g_tgid = 0;
static uint32_t g_name_hash = 0;

/* 环形样本：存的是"上一帧的持续时长(ns)"。
 * 用 __atomic 而不是裸赋值：armv7 上 8 字节读写不是原子的，
 * 撕裂的高位会让某一帧看起来像 4 秒 —— 那正是我们要检测的 jank，不能是假警报。 */
static _Atomic int64_t  g_ring[RING_CAP];
static _Atomic uint32_t g_head;
static _Atomic int64_t  g_last_ns;
static _Atomic int64_t  g_window_start_ns;
static _Atomic int64_t  g_last_report_ns;
static _Atomic int64_t  g_next_try_ns;
static _Atomic int32_t  g_swap_interval = -1;
/* 低 32 位 = 大卡顿次数，高 32 位 = 小卡顿次数；一个原子量顶两个计数器 */
static _Atomic uint64_t g_pack;
static atomic_flag g_report_lock = ATOMIC_FLAG_INIT;

/* ------------------------------------------------------------------ */
/* 工具                                                                */
/* ------------------------------------------------------------------ */

/* FNV-1a：让 Daemon 能区分同一 app 的多个进程（:shader / :pusher），
 * 又不必把变长字符串塞进定长报文里。 */
static uint32_t fnv1a(const char *s)
{
    uint32_t h = 2166136261u;
    for (; *s; ++s) { h ^= (uint8_t)*s; h *= 16777619u; }
    return h;
}

/*
 * 进程黑名单守卫。
 * 探针只应该进"某个 app 进程"。system_server / surfaceflinger 一旦被注入，
 * 出错就是整机卡死或黑屏，而这两个恰好是 `wrap.` 属性名拼写错误最容易命中的。
 * 这份名单不可被环境变量绕过 —— 这是刻意的。
 */
static int process_forbidden(const char *comm)
{
    static const char *exact_deny[] = {
        "init", "system_server", "surfaceflinger", "zygote", "zygote64",
        "app_process", "app_process64", "mediadrmserver",
        "entropyd", "entropy-monitor", "entropy-freezer", NULL
    };
    for (int i = 0; exact_deny[i]; ++i)
        if (!strcmp(comm, exact_deny[i])) return 1;
    /* app 进程的 argv[0] 一定是包名（含点）。纯路径进程一律拒绝：
     * /vendor/bin/hw/... 这类 HAL 挂了探针出问题，用户只会看到"手机黑了"。 */
    return strchr(comm, '.') == NULL;
}

/* 构造期安全：只用 RTLD_NEXT，不碰 dlopen。 */
static void *next_sym(const char *sym) { return dlsym(RTLD_NEXT, sym); }

/* 运行期兜底：极少数 ROM 上 libEGL 不在本 so 的查找链里，显式取一次句柄。
 * 只在真符号缺失时走，且只走一次。 */
static void *late_sym(const char *sym)
{
    void *p = next_sym(sym);
    if (p) return p;
    void *h = dlopen("libEGL.so", RTLD_NOW | RTLD_GLOBAL);
    return h ? dlsym(h, sym) : NULL;
}

/* ------------------------------------------------------------------ */
/* 上报                                                               */
/* ------------------------------------------------------------------ */

/* 插入排序：帧时长数组天然近似有序（稳态帧时几乎相同），插入排序在这种输入上
 * 接近 O(n)；而 qsort 可能 malloc —— 探针里不能出现 malloc。 */
static void sort_ns(int64_t *a, int n)
{
    for (int i = 1; i < n; ++i) {
        int64_t v = a[i];
        int j = i - 1;
        while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; --j; }
        a[j + 1] = v;
    }
}

static void try_connect(int64_t now)
{
    if (g_sock >= 0) return;
    if (now < atomic_load_explicit(&g_next_try_ns, memory_order_relaxed)) return;
    atomic_store_explicit(&g_next_try_ns, now + RETRY_MIN_NS, memory_order_relaxed);

    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';                       /* abstract namespace */
    memcpy(addr.sun_path + 1, ENTROPY_HOOK_SOCK_NAME, strlen(ENTROPY_HOOK_SOCK_NAME));
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 +
                                 (int)strlen(ENTROPY_HOOK_SOCK_NAME));
    /* DGRAM 的 connect 只是给 send 定好对端；Daemon 不在时立刻返回 ENOENT，
     * 我们安静关掉，等下一次 30s 退避窗口。 */
    if (connect(fd, (struct sockaddr *)&addr, alen) != 0) { close(fd); return; }
    int sndbuf = 8192;                             /* 定长小包，不需要内核囤积 */
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    g_sock = fd;
}

static void flush_window(void)
{
    /* 同一时刻只允许一个线程做统计；其他线程直接跳过，样本留在环里下一轮再算。 */
    if (atomic_flag_test_and_set(&g_report_lock)) return;

    int64_t now = now_ns();
    int64_t win_start = atomic_load_explicit(&g_window_start_ns, memory_order_relaxed);

    uint32_t head = atomic_load_explicit(&g_head, memory_order_relaxed);
    uint32_t avail = head < RING_CAP ? head : RING_CAP;
    int64_t samples[RING_CAP];
    int n = 0;
    for (uint32_t i = 0; i < avail; ++i) {
        int64_t v = atomic_load_explicit(&g_ring[(head - 1u - i) & RING_MASK], memory_order_relaxed);
        if (v <= 0 || v > 2000000000ll) continue;   /* 非法 / 超 2s（后台挂起）不计入 */
        samples[n++] = v;
    }

    if (n >= 4 && now - win_start >= REPORT_MIN_NS) {
        sort_ns(samples, n);
        /* 丢掉最慢的一格再统计：切后台和首帧那种 500ms 极端值会把均值压成噪声，
         * 而它本来就不是"卡顿"，是生命周期事件。 */
        int usable = n - 1;
        int64_t sum = 0;
        for (int i = 0; i < usable; ++i) sum += samples[i];
        int p95 = (int)(((int64_t)usable * 95) / 100);
        if (p95 > usable - 1) p95 = usable - 1;

        entropy_hook_report rep;
        memset(&rep, 0, sizeof(rep));
        rep.pid = g_pid;
        rep.tgid = g_tgid;
        rep.window_start_ns = win_start;
        rep.window_end_ns = now;
        rep.frames = (uint32_t)usable;
        rep.frame_ms_sum = sum * 1000;              /* 定点 µs，Daemon 除以帧数得均值 */
        rep.frame_ms_p50 = samples[usable / 2] * 1000;
        rep.frame_ms_p95 = samples[p95] * 1000;
        rep.swap_interval = (int32_t)atomic_load_explicit(&g_swap_interval, memory_order_relaxed);
        rep.app_name_hash = g_name_hash;
        uint64_t packed = atomic_load_explicit(&g_pack, memory_order_relaxed);
        rep.jank_big = (uint32_t)(packed & 0xFFFFFFFFu);
        rep.jank_small = (uint32_t)(packed >> 32);

        try_connect(now);
        if (g_sock >= 0) {
            /* 帧头照发：让 Daemon 用同一套 magic/version 校验拒掉乱连的进程 */
            entropy_frame_hdr hdr;
            memset(&hdr, 0, sizeof(hdr));
            hdr.magic = ENTROPY_MAGIC;
            hdr.version = ENTROPY_PROTO_VER;
            hdr.type = ENT_CMD_HOOK_REPORT;
            hdr.seq = g_pid;
            hdr.payload_len = sizeof(rep);
            struct iovec iov[2] = { { &hdr, sizeof(hdr) }, { &rep, sizeof(rep) } };
            struct msghdr msg;
            memset(&msg, 0, sizeof(msg));
            msg.msg_iov = iov;
            msg.msg_iovlen = 2;
            if (sendmsg(g_sock, &msg, MSG_DONTWAIT) < 0) {
                /* ENOENT/ECONNREFUSED = Daemon 退了；其余是内核缓冲满，丢一帧无所谓 */
                if (errno == ENOENT || errno == ECONNREFUSED || errno == ENETDOWN) {
                    close(g_sock);
                    g_sock = -1;
                }
            }
        }
    }

    /* 窗口与计数复位：jank 是本窗口的计数，跨窗口累加会让 UI 显示只增不减的假数字 */
    atomic_store_explicit(&g_window_start_ns, now, memory_order_relaxed);
    atomic_store_explicit(&g_pack, 0, memory_order_relaxed);
    atomic_flag_clear(&g_report_lock);
}

/* ------------------------------------------------------------------ */
/* 采样                                                               */
/* ------------------------------------------------------------------ */

static inline void record_swap(int64_t done_ns)
{
    int64_t prev = atomic_exchange_explicit(&g_last_ns, done_ns, memory_order_relaxed);
    if (prev <= 0) return;                       /* 第一次 swap 没有间隔可言 */
    int64_t dt = done_ns - prev;
    if (dt <= 0) return;                         /* 时钟异常，丢弃 */

    uint32_t idx = (uint32_t)atomic_fetch_add_explicit(&g_head, 1u, memory_order_relaxed);
    atomic_store_explicit(&g_ring[idx & RING_MASK], dt, memory_order_relaxed);

    /* jank 基线用"前 3 帧均值"：它跟着实时帧率走，60Hz 与 120Hz 设备
     * 能用同一条阈值，不需要按刷新率配置。 */
    int64_t a = atomic_load_explicit(&g_ring[(idx - 1u) & RING_MASK], memory_order_relaxed);
    int64_t b = atomic_load_explicit(&g_ring[(idx - 2u) & RING_MASK], memory_order_relaxed);
    int64_t c = atomic_load_explicit(&g_ring[(idx - 3u) & RING_MASK], memory_order_relaxed);
    if (a > 0 && b > 0 && c > 0) {
        int64_t base = (a + b + c) / 3;
        if (dt > base * 2) {
            /* 大卡顿另设 32ms 绝对门槛：基线本身只有 8ms 时，
             * 翻两倍(16ms)其实仍是一帧正常输出，不该报。 */
            uint64_t add = dt > 32000000ll ? 1u : (1ull << 32);
            atomic_fetch_add_explicit(&g_pack, add, memory_order_relaxed);
        }
    }

    if (done_ns - atomic_load_explicit(&g_last_report_ns, memory_order_relaxed) >= REPORT_MIN_NS) {
        atomic_store_explicit(&g_last_report_ns, done_ns, memory_order_relaxed);
        flush_window();
    }
}

/* ------------------------------------------------------------------ */
/* 生命周期                                                           */
/* ------------------------------------------------------------------ */

__attribute__((constructor)) static void entropy_hook_ctor(void)
{
    if (getenv("ENTROPY_HOOK_DISABLE")) return;

    g_in_ctor = 1;
    /* /proc/self/cmdline 是 NUL 分隔的 argv，取第一段作为进程名 */
    char raw[256];
    memset(raw, 0, sizeof(raw));
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, raw, sizeof(raw) - 1);
        close(fd);
        if (n <= 0) { g_in_ctor = 0; return; }
    } else {
        g_in_ctor = 0;
        return;                        /* 读不到就不装：探针失踪好过装错地方 */
    }
    if (process_forbidden(raw)) { g_in_ctor = 0; return; }

    /* 目标过滤：wrap.<pkg> 是整包生效的，用户可能只想测主进程。
     * ENTROPY_HOOK_TARGET 传 "com.x.y" 匹配主进程，传 "com.x.y:" 前缀匹配所有子进程。 */
    const char *want = getenv("ENTROPY_HOOK_TARGET");
    if (want && *want) {
        size_t w = strlen(want);
        int all = want[w - 1] == ':';
        int hit = all ? !strncmp(raw, want, w - 1) : !strcmp(raw, want);
        if (!hit) { g_in_ctor = 0; return; }
    }

    g_name_hash = fnv1a(raw);
    g_pid = (uint32_t)getpid();
    /* bionic 的 gettid() 要 API 29，探针要能进 Android 7 的进程 */
    g_tgid = (uint32_t)syscall(SYS_gettid);

    swap_fn s = (swap_fn)next_sym("eglSwapBuffers");
    if (!s) { g_in_ctor = 0; return; }   /* 拿不到真实现必须放弃：自己实现等于篡改结果 */
    atomic_store_explicit(&real_swap, s, memory_order_relaxed);
    atomic_store_explicit(&real_swapi,
        (swapi_fn)next_sym("eglSwapInterval"), memory_order_relaxed);

    int64_t t0 = now_ns();
    atomic_store_explicit(&g_window_start_ns, t0, memory_order_relaxed);
    atomic_store_explicit(&g_last_report_ns, t0, memory_order_relaxed);
    try_connect(t0);                     /* 连不上也没关系，record_swap 里按 30s 退避重试 */
    g_in_ctor = 0;
    g_enabled = 1;
}

__attribute__((destructor)) static void entropy_hook_dtor(void)
{
    if (g_enabled) flush_window();
    if (g_sock >= 0) { close(g_sock); g_sock = -1; }
}

/* ------------------------------------------------------------------ */
/* 导出：被劫持的符号                                                  */
/* ------------------------------------------------------------------ */

/* 可见性：全局默认是 -fvisibility=hidden，劫持靠 LD_PRELOAD 的符号查找顺序，
 * 这两个符号必须显式导出，否则 .so 装上了也拦不到调用。 */

__attribute__((visibility("default")))
ent_eglbool eglSwapBuffers(void *dpy, void *surf)
{
    swap_fn f = atomic_load_explicit(&real_swap, memory_order_relaxed);
    if (!f) {
        /* 兜底：本进程不在探针职责内（构造期已放弃绑定），但符号确实被劫持了，
         * 必须原样转发 —— 替 app 决定"这次换帧失败"是不可接受的副作用。 */
        f = (swap_fn)late_sym("eglSwapBuffers");
        if (!f) return 0;
        atomic_store_explicit(&real_swap, f, memory_order_relaxed);
    }
    ent_eglbool r = f(dpy, surf);
    if (g_enabled && r == ENT_EGL_TRUE) record_swap(now_ns());
    return r;
}

/* swapInterval=1 时驱动会睡到 vsync，帧间隔里就混进了等待时间；
 * 记下它，Daemon 才能判断"16.7ms"是垂直同步的结果还是渲染的结果。 */
__attribute__((visibility("default")))
ent_eglbool eglSwapInterval(void *dpy, ent_eglint interval)
{
    atomic_store_explicit(&g_swap_interval, interval, memory_order_relaxed);
    swapi_fn f = atomic_load_explicit(&real_swapi, memory_order_relaxed);
    if (!f) {
        f = (swapi_fn)late_sym("eglSwapInterval");
        atomic_store_explicit(&real_swapi, f, memory_order_relaxed);
        if (!f) return 0;
    }
    return f(dpy, interval);
}

/* 给 Daemon 的 --hook-status 与现场调试用的自省入口：不依赖 IPC 就能确认
 * "这个进程里到底有没有真正装上探针"，比翻日志快得多。 */
__attribute__((visibility("default")))
int entropy_hook_active(void) { return g_enabled; }
