/*
 * File: daemon/src/ipc_server.cpp
 * 设计说明见 ipc_server.h。
 */
#include "ipc_server.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <poll.h>
#include <set>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

/* ---------------- 共享内存 seqlock ---------------- */
/*
 * 为什么用 seqlock 而不是 mutex/atomic 拷贝：
 *  App 侧是只读读者，绝不能因为 Daemon 正在写就阻塞或被杀死；
 *  读者做「读-校验版本号-重读」，只要版本没变就是原子快照，
 *  变更了就重试。写侧代价仅两次 release fence，完全符合 Daemon <1% CPU 的约束。
 */
struct shm_layout {
    std::atomic<uint32_t> seq;
    entropy_telemetry     t;
    entropy_probe_result  probe;
    entropy_heartbeat     hb;
    uint32_t              proto_ver;
};
void            *g_shm = nullptr;
std::atomic<int>  g_shm_fd{-1};

/* ---------------- 连接管理 ---------------- */
struct Client {
    int fd;
    uint32_t pid;
    uint32_t uid;
    std::vector<uint8_t> rx;
};
std::mutex   g_cli_mtx;
std::vector<Client> g_clients;

entropy_ipc_handlers g_h{};
std::atomic<bool> g_run{false};
std::thread       g_accept_thr;
int               g_listen_fd = -1;

/* ---------------- 帧率探针接收端 ---------------- */
/*
 * 见 ipc_server.h 的注释：这条通道只进不出、只收一种帧。
 * 之所以不值得做鉴权：上报内容是"某进程自己画了多少帧"，它既不能改频率也不能
 * 改属性，最坏的滥用是伪造自己的帧率骗过 UI 显示 —— 而这条数据不进熔断逻辑。
 * 但必须限流：否则任意进程能用海量 pid 把这张表撑爆。
 */
struct HookSink {
    std::mutex mtx;
    std::map<uint32_t, entropy_hook_report> by_tgid;   /* tgid -> 最近一窗 */
    int      fd = -1;
    std::thread thr;
    std::atomic<bool> run{false};
};
HookSink g_hook;
constexpr size_t kHookMaxPeers = 32;          /* 同时测 32 个进程早就够用了 */
constexpr int64_t kHookFreshNs = 1500000000;  /* 1.5s 内没上报就算探针离线 */

static int64_t hook_monotonic_ns()
{
    struct timespec ts{0, 0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000ll + ts.tv_nsec;
}

void hook_recv_loop()
{
    /* 一帧 = 16B 头 + 88B 负载；收不到整帧直接丢，DGRAM 不存在半包 */
    uint8_t buf[512];
    while (g_hook.run.load()) {
        /* 带超时轮询而不是死等 recvmsg：DGRAM 上的 shutdown() 唤不醒阻塞的读者，
         * 而退出时必须让 Daemon 的关闭路径在几十毫秒内走干净。 */
        pollfd pfd{ g_hook.fd, POLLIN, 0 };
        int pr = poll(&pfd, 1, 200);
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pr == 0) continue;
        struct iovec iov { buf, sizeof(buf) };
        char ctrl[CMSG_SPACE(sizeof(struct ucred))] = {0};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl;
        msg.msg_controllen = sizeof(ctrl);
        ssize_t n = recvmsg(g_hook.fd, &msg, MSG_CMSG_CLOEXEC);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if ((size_t)n < sizeof(entropy_frame_hdr) + sizeof(entropy_hook_report)) continue;

        /* SO_PASSCRED 让内核附上发送方凭据。这里只用它做一件事：
         * 确认上报者是"活着的某个进程"，pid 与报文里自报的 tgid 对得上，
         * 防止有人拿别人的 pid 冒报帧率把 UI 曲线搞乱。 */
        struct ucred cred{};
        bool have_cred = false;
        for (cmsghdr *cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm))
            if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_CREDENTIALS) {
                memcpy(&cred, CMSG_DATA(cm), sizeof(cred));
                have_cred = true;
            }

        entropy_frame_hdr hdr;
        memcpy(&hdr, buf, sizeof(hdr));
        if (hdr.magic != ENTROPY_MAGIC || hdr.version != ENTROPY_PROTO_VER ||
            hdr.type != ENT_CMD_HOOK_REPORT ||
            hdr.payload_len != sizeof(entropy_hook_report)) continue;

        entropy_hook_report rep;
        memcpy(&rep, buf + sizeof(hdr), sizeof(rep));
        if (have_cred && (uint32_t)cred.pid != rep.tgid) continue;
        if (rep.frames == 0 || rep.frame_ms_sum <= 0) continue;
        if (rep.window_end_ns <= rep.window_start_ns) continue;

        std::lock_guard<std::mutex> g(g_hook.mtx);
        auto it = g_hook.by_tgid.find(rep.tgid);
        if (it == g_hook.by_tgid.end()) {
            if (g_hook.by_tgid.size() >= kHookMaxPeers) {
                /* 表满：踢掉最旧的那个，而不是拒绝新的 —— 前台 app 往往最后登场 */
                auto oldest = g_hook.by_tgid.begin();
                for (auto p = g_hook.by_tgid.begin(); p != g_hook.by_tgid.end(); ++p)
                    if (p->second.window_end_ns < oldest->second.window_end_ns) oldest = p;
                g_hook.by_tgid.erase(oldest);
            }
            g_hook.by_tgid[rep.tgid] = rep;
        } else if (rep.window_end_ns >= it->second.window_end_ns) {
            it->second = rep;
        }
    }
}

/*
 * 准入控制。UDS 文件在 /data/local/tmp，权限 0660 + 属主 root:shell，
 * 再叠加 SO_PEERCRED 校验，防止任意第三方 app 连进来下发 setprop。
 * 这是整个项目里最重要的一道安全边界：Daemon 是 root 权限的，
 * 任何"能连上就能改频率"的设计都等于给所有 app 开了提权口子。
 *
 * 熵的 App 本身是普通 uid，怎么连？
 *   由用户在授权步骤显式把自己的 uid 写进 /data/local/tmp/entropy.allow：
 *     Root:  su -c 'echo $((uid)) > /data/local/tmp/entropy.allow'
 *     ADB:   adb shell 'echo 10xxx > /data/local/tmp/entropy.allow'
 *   该文件属主 root、权限 0600 —— 只有已经拿到 root/shell 的人能改，
 *   恶意 app 无法把自己加进白名单。App 每次连不上时 UI 会给出对应命令。
 */
bool peer_allowed(uint32_t uid)
{
    /* root(0) / shell(2000) / system(1000) —— 熵的 app 通过 su 或 ADB shell 进入其中之一 */
    if (uid == 0 || uid == 2000 || uid == 1000) return true;

    FILE *f = fopen("/data/local/tmp/entropy.allow", "r");
    if (!f) return false;
    struct stat st;
    if (fstat(fileno(f), &st) == 0 && (st.st_mode & 0077) != 0) {
        /* 权限过松的白名单文件不可信（别的 app 也可能写进去了），直接拒绝 */
        fclose(f);
        return false;
    }
    char line[32];
    bool ok = false;
    while (fgets(line, sizeof(line), f)) {
        if (strtoul(line, nullptr, 10) == uid) { ok = true; break; }
    }
    fclose(f);
    return ok;
}

int send_frame(int fd, uint16_t type, uint32_t seq, const void *payload, uint32_t len)
{
    entropy_frame_hdr hdr{};
    hdr.magic = ENTROPY_MAGIC;
    hdr.version = ENTROPY_PROTO_VER;
    hdr.type = type;
    hdr.seq = seq;
    hdr.payload_len = len;
    struct iovec iov[2] = { { &hdr, sizeof(hdr) }, { const_cast<void *>(payload), len } };
    msghdr msg{};
    msg.msg_iov = iov;
    msg.msg_iovlen = len ? 2 : 1;
    /* MSG_NOSIGNAL：对端断开时不要 SIGPIPE 打死 Daemon */
    ssize_t n = sendmsg(fd, &msg, MSG_NOSIGNAL);
    return n < 0 ? -1 : 0;
}

void publish_shm(const entropy_telemetry *t)
{
    if (!g_shm) return;
    auto *L = (shm_layout *)g_shm;
    uint32_t s = L->seq.load(std::memory_order_relaxed);
    L->seq.store(s + 1, std::memory_order_release);   /* 奇数 = 写中 */
    L->t = *t;
    L->seq.store(s + 2, std::memory_order_release);   /* 偶数 = 一致 */
}

/* ---------------- 请求处理 ---------------- */
void handle_request(Client &c, const entropy_frame_hdr &hdr, const uint8_t *pl)
{
    entropy_ack ack{};
    ack.code = 0;
    const void *resp = &ack;
    uint32_t resp_len = sizeof(ack);

    switch (hdr.type) {
    case ENT_CMD_PING:
        snprintf(ack.msg, sizeof(ack.msg), "entropyd alive proto=%u", ENTROPY_PROTO_VER);
        break;
    case ENT_CMD_APPLY_PROFILE:
        if (hdr.payload_len < sizeof(entropy_profile_req)) { ack.code = -EMSGSIZE; break; }
        ack.code = g_h.on_profile ? g_h.on_profile((const entropy_profile_req *)pl) : -ENOSYS;
        break;
    case ENT_CMD_SET_LIMIT:
        if (hdr.payload_len < sizeof(entropy_limit_req)) { ack.code = -EMSGSIZE; break; }
        ack.code = g_h.on_limit ? g_h.on_limit((const entropy_limit_req *)pl) : -ENOSYS;
        break;
    case ENT_CMD_START_PROBE:
        if (hdr.payload_len < sizeof(entropy_probe_req)) { ack.code = -EMSGSIZE; break; }
        ack.code = g_h.on_probe ? g_h.on_probe((const entropy_probe_req *)pl) : -ENOSYS;
        break;
    case ENT_CMD_STOP_PROBE:
        ack.code = g_h.on_stop_probe ? g_h.on_stop_probe() : -ENOSYS;
        break;
    case ENT_CMD_RESET_DEFAULT:
        ack.code = g_h.on_reset ? g_h.on_reset() : -ENOSYS;
        break;
    case ENT_CMD_SET_MONITOR:
        if (hdr.payload_len < sizeof(entropy_monitor_cfg)) { ack.code = -EMSGSIZE; break; }
        ack.code = g_h.on_monitor_cfg ? g_h.on_monitor_cfg((const entropy_monitor_cfg *)pl) : -ENOSYS;
        break;
    case ENT_CMD_FREEZE_PKG:
    case ENT_CMD_UNFREEZE_PKG: {
        /* payload 是 NUL 结尾包名，必须硬截断，绝不能让越界读写发生 */
        char pkg[128] = {0};
        uint32_t n = std::min<uint32_t>(hdr.payload_len, sizeof(pkg) - 1);
        memcpy(pkg, pl, n);
        pkg[n] = '\0';
        for (char *p = pkg; *p; ++p)
            if (*p == ';' || *p == '|' || *p == '&' || *p == '`' || *p == '$' || *p == '\n') {
                ack.code = -EINVAL;          /* 包名不该有 shell 元字符 */
                snprintf(ack.msg, sizeof(ack.msg), "illegal char in package name");
                goto reply;
            }
        ack.code = g_h.on_freeze ? g_h.on_freeze(pkg, hdr.type == ENT_CMD_FREEZE_PKG ? 1 : 0) : -ENOSYS;
        break;
    }
    case ENT_CMD_SET_NODE: {
        char spec[320] = {0};
        uint32_t n = std::min<uint32_t>(hdr.payload_len, sizeof(spec) - 1);
        memcpy(spec, pl, n);
        spec[n] = '\0';
        ack.code = g_h.on_node ? g_h.on_node(spec) : -ENOSYS;
        break;
    }
    default:
        ack.code = -EPROTONOSUPPORT;
        break;
    }

reply:
    send_frame(c.fd, ENT_REP_ACK, hdr.seq, resp, resp_len);
}

void client_loop(Client c)
{
    std::vector<uint8_t> buf;
    buf.reserve(4096);
    bool alive = true;
    while (alive && g_run.load()) {
        uint8_t tmp[512];
        ssize_t n = recv(c.fd, tmp, sizeof(tmp), 0);
        if (n <= 0) { alive = false; break; }
        buf.insert(buf.end(), tmp, tmp + n);

        /* 粘包处理：TCP 风格的字节流，按帧头长度切 */
        while (buf.size() >= sizeof(entropy_frame_hdr)) {
            entropy_frame_hdr hdr;
            memcpy(&hdr, buf.data(), sizeof(hdr));
            if (hdr.magic != ENTROPY_MAGIC || hdr.version != ENTROPY_PROTO_VER) {
                /* 协议不符立即断开：这基本等价于有人拿别的工具连了这个口 */
                alive = false;
                break;
            }
            size_t need = sizeof(hdr) + hdr.payload_len;
            if (hdr.payload_len > 64 * 1024) { alive = false; break; }   /* 拒绝超大帧 */
            if (buf.size() < need) break;                                  /* 等更多字节 */
            handle_request(c, hdr, buf.data() + sizeof(hdr));
            buf.erase(buf.begin(), buf.begin() + (long)need);
        }
    }
    close(c.fd);
    std::lock_guard<std::mutex> g(g_cli_mtx);
    g_clients.erase(std::remove_if(g_clients.begin(), g_clients.end(),
                                   [&](const Client &x) { return x.fd == c.fd; }),
                    g_clients.end());
}

void accept_loop()
{
    /* 允许 SIGPIPE 被忽略即可；MSG_NOSIGNAL 已经处理了写侧 */
    signal(SIGPIPE, SIG_IGN);
    while (g_run.load()) {
        struct sockaddr_un addr{};
        socklen_t alen = sizeof(addr);
        int fd = accept(g_listen_fd, (struct sockaddr *)&addr, &alen);
        if (fd < 0) {
            if (errno == EINTR) continue;
            if (errno == EMFILE || errno == ENFILE) {
                /* 文件句柄耗尽：退避而不是空转刷屏 */
                usleep(200000);
                continue;
            }
            break;
        }
        struct ucred cred{};
        socklen_t clen = sizeof(cred);
        if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) != 0 || !peer_allowed(cred.uid)) {
            entropy_ack a{ -EACCES, "uid not permitted" };
            send_frame(fd, ENT_REP_ACK, 0, &a, sizeof(a));
            close(fd);
            continue;
        }
        int sndbuf = 256 * 1024;
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
        struct timeval tv{ 2, 0 };            /* 2s 写超时：慢客户端不会吊死广播线程 */
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        Client c{ fd, (uint32_t)cred.pid, (uint32_t)cred.uid, {} };
        {
            std::lock_guard<std::mutex> g(g_cli_mtx);
            g_clients.push_back(c);
        }
        std::thread(client_loop, c).detach();
    }
}

}  // namespace

/* ---------------- 公共 API ---------------- */

int ent_ipc_shm_init(void)
{
    /*
     * 优先用 memfd_create：它有真正的 fd，可以通过 SCM_RIGHTS 传给连接方，
     * 且不依赖 /dev/shm 在 Android 上的挂载情况（多数 ROM 没有 /dev/shm）。
     * NDK 的 syscall 表在低 API 上没有这个符号，按架构补编号。
     */
#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__arm__)
#define __NR_memfd_create 385
#endif
#endif
    int fd = -1;
#ifdef __NR_memfd_create
    fd = (int)syscall(__NR_memfd_create, "entropy_tel", 0 /* MFD_CLOEXEC */);
#endif
    if (fd < 0) return -1;
    if (ftruncate(fd, ENTROPY_SHM_SIZE) != 0) { close(fd); return -1; }
    void *p = mmap(nullptr, ENTROPY_SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) { close(fd); return -1; }
    memset(p, 0, ENTROPY_SHM_SIZE);
    ((shm_layout *)p)->proto_ver = ENTROPY_PROTO_VER;
    g_shm = p;
    g_shm_fd = fd;
    return 0;
}

void ent_ipc_shm_publish(const entropy_telemetry *t) { publish_shm(t); }
int  ent_ipc_shm_fd(void) { return g_shm_fd.load(); }

int ent_ipc_broadcast(uint16_t type, const void *payload, uint32_t len)
{
    std::lock_guard<std::mutex> g(g_cli_mtx);
    int sent = 0;
    for (auto &c : g_clients)
        if (send_frame(c.fd, type, 0, payload, len) == 0) sent++;
    return sent;
}

int ent_ipc_start(const entropy_ipc_handlers *h)
{
    if (!h) return -1;
    g_h = *h;

    /* 先尝试 abstract namespace（前缀 '\0'），它不受 /data 权限与 SELinux 文件标签影响，
     * ADB 模式与 Magisk 模式都能连；失败再退回文件系统路径。 */
    unlink(ENTROPY_SOCK_PATH);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const char *name = "entropy";
    /* abstract：sun_path[0] = '\0'，后面跟名字 */
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, strlen(name));
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + (int)strlen(name));

    int rc = bind(fd, (struct sockaddr *)&addr, alen);
    bool is_abstract = (rc == 0);
    if (!is_abstract) {
        snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", ENTROPY_SOCK_PATH);
        alen = (socklen_t)sizeof(addr);
        rc = bind(fd, (struct sockaddr *)&addr, alen);
    }
    if (rc != 0) { close(fd); return -1; }
    if (!is_abstract) {
        chmod(ENTROPY_SOCK_PATH, 0660);
        /* Magisk 环境下补 SELinux 标签，否则 untrusted_app 一律 connect 不上 */
        int r = system("restorecon " ENTROPY_SOCK_PATH " 2>/dev/null");
        (void)r;
    }
    if (listen(fd, 8) != 0) { close(fd); return -1; }
    g_listen_fd = fd;
    g_run.store(true);
    g_accept_thr = std::thread(accept_loop);
    return 0;
}

void ent_ipc_stop(void)
{
    g_run.store(false);
    if (g_listen_fd >= 0) {
        shutdown(g_listen_fd, SHUT_RDWR);
        close(g_listen_fd);
        g_listen_fd = -1;
    }
    if (g_accept_thr.joinable()) g_accept_thr.join();
    if (g_shm) { munmap(g_shm, ENTROPY_SHM_SIZE); g_shm = nullptr; }
    if (g_shm_fd.load() >= 0) { close(g_shm_fd.load()); g_shm_fd = -1; }
    unlink(ENTROPY_SOCK_PATH);
    ent_ipc_hook_stop();
}

/* ---------------- 帧率探针通道 ---------------- */

int ent_ipc_hook_start(void)
{
    if (g_hook.fd >= 0) return 0;
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, ENTROPY_HOOK_SOCK_NAME, strlen(ENTROPY_HOOK_SOCK_NAME));
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 +
                                 (int)strlen(ENTROPY_HOOK_SOCK_NAME));
    if (bind(fd, (struct sockaddr *)&addr, alen) != 0) { close(fd); return -1; }

    /* 内核替我们把发送方凭据附在控制消息里，不需要 hook 侧自己塞 SCM_CREDENTIALS
     * （那要求发送方对目标有 CAP_SYS_ADMIN，普通 app 永远做不到） */
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));
    int rcvbuf = 32 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    g_hook.run.store(true);
    g_hook.fd = fd;
    g_hook.thr = std::thread(hook_recv_loop);
    return 0;
}

void ent_ipc_hook_stop(void)
{
    if (g_hook.fd >= 0) {
        shutdown(g_hook.fd, SHUT_RDWR);
        g_hook.run.store(false);
        if (g_hook.thr.joinable()) g_hook.thr.join();
        close(g_hook.fd);
        g_hook.fd = -1;
    }
    std::lock_guard<std::mutex> g(g_hook.mtx);
    g_hook.by_tgid.clear();
}

int ent_ipc_hook_latest(entropy_hook_report *out)
{
    if (!out || g_hook.fd < 0) return 0;
    const int64_t now = hook_monotonic_ns();
    std::lock_guard<std::mutex> g(g_hook.mtx);
    const entropy_hook_report *best = nullptr;
    for (auto it = g_hook.by_tgid.begin(); it != g_hook.by_tgid.end();) {
        /* 顺带回收过期条目：被注入的进程退出后再也不会更新，留着会占表位 */
        if (it->second.window_end_ns + kHookFreshNs < now) {
            it = g_hook.by_tgid.erase(it);
            continue;
        }
        if (!best || it->second.window_end_ns > best->window_end_ns) best = &it->second;
        ++it;
    }
    /* 上报侧和 Daemon 读的是同一个 CLOCK_MONOTONIC，跨进程可直接比较；
     * 超过 1.5s 静默说明那个 app 已经退出前台或探针被卸了。 */
    if (!best || best->window_end_ns + kHookFreshNs < now) return 0;
    *out = *best;
    return 1;
}
