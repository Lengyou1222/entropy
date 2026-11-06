# File: daemon/CMakeLists.txt
#
# 独立构建脚本：既能在 Android Gradle (externalNativeBuild) 里被 include，
# 也能用 NDK clang 单独 cmake 出静态二进制推送到 /system/bin 调试。
#
# 关键约束：
#  - Daemon 必须不依赖 libandroid_runtime / ART，否则会被 Zygote 域拖住。
#  - 全部静态链接 C++ 运行时(-static-libstdc++)，Magisk 模块里就不用带 libc++_shared.so。
#  - eBPF 对象不内嵌进 entropyd：libbpf 的 bpf_object__open_file() 需要真实路径，
#    所以它是个独立产物 entropy_sched.bpf.o，由模块的 service.sh 放到
#    /data/local/tmp 供运行时读取。默认不构建，见下面的 ENTROPY_BUILD_BPF。

cmake_minimum_required(VERSION 3.22.1)
project(entropy_daemon C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)

set(ENTROPY_COMMON_FLAGS
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden
    -fno-exceptions -fno-rtti
    -Os                      # Daemon 体积与指令缓存友好度优先
    -D_FORTIFY_SOURCE=2
    -fstack-protector-strong)

add_compile_options(${ENTROPY_COMMON_FLAGS})
add_compile_definitions(__ENTROPY_DAEMON__=1)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src ${CMAKE_CURRENT_SOURCE_DIR}/../shared/protocol)

# ---------- 调度核心 ----------
add_executable(entropyd
    src/main.cpp
    src/sysfs.c
    src/ipc_server.cpp
    src/scheduler.cpp
    src/safety_fuse.cpp
    src/limit_detector.cpp
    src/ebpf_loader.cpp)

# 静态链接 STL：Magisk 包里只带一个文件，避免设备上找不到 libc++_shared
target_link_options(entropyd PRIVATE
    -static-libstdc++ -static-libgcc
    -Wl,--build-id=sha1
    -Wl,-s)   # 发布时 strip，符号留在 NDK obj 目录里用于反解

target_link_libraries(entropyd PRIVATE log m)

# ---------- 温控守护（纯 C，独立进程，崩溃不影响调度核心）----------
add_executable(entropy-monitor src/thermal_monitor.c src/sysfs.c)
target_link_options(entropy-monitor PRIVATE -static-libgcc -Wl,-s)

# ---------- 后台真·暂停（cgroup freezer）----------
add_executable(entropy-freezer src/bg_freezer.c src/sysfs.c)
target_link_options(entropy-freezer PRIVATE -static-libgcc -Wl,-s)

# ---------- LD_PRELOAD 帧节奏探针 ----------
# 唯一一个"装进别人的进程"的产物，所以它的依赖必须尽量少：
#  - 不链 libEGL（真符号靠 dlsym(RTLD_NEXT) 现取），否则注入一个没加载
#    libEGL 的进程就会因为缺库而启动失败；
#  - 不链 libc++，纯 C；
#  - -fvisibility=hidden 是全局默认，源文件里两个被劫持符号已显式 default 导出。
add_library(entropy_hook SHARED src/native_hook.c)
set_target_properties(entropy_hook PROPERTIES OUTPUT_NAME entropy_hook C_VISIBILITY_PRESET hidden)
target_link_libraries(entropy_hook PRIVATE dl)
if(CMAKE_SIZEOF_VOID_P EQUAL 4)
    # armv7 上 8 字节原子量要 libatomic（x86-32 同理）。64 位 ABI 是原生指令，不需要。
    target_link_libraries(entropy_hook PRIVATE atomic)
endif()
target_link_options(entropy_hook PRIVATE -Wl,-s -Wl,--gc-sections)
# 探针自己不带版本号，但必须带 build-id：现场 crash 反解时只有它能把
# "libentropy_hook.so+0x1a3c" 对回源码行。
target_link_options(entropy_hook PRIVATE -Wl,--build-id=sha1)

# ---------- eBPF 对象 ----------
# 刻意默认关闭，而不是"找到 clang 就顺手编"。
# 这个文件需要 bpf/bpf_helpers.h（NDK sysroot 里根本没有）和内核 BTF 头，
# 只有在装了 libbpf-dev 的 Linux 宿主机上才编得出来。把它做成隐式依赖的后果是：
# 任何 PATH 上有 clang 的机器都会在编 entropyd 时因为缺个头文件而整体失败，
# 而运行时 ebpf_loader 本来就有 perf/schedstat 兜底路径，功能不中断。
# 要编它：-DENTROPY_BUILD_BPF=ON，产物是 entropy_sched.bpf.o，
# 随 Magisk 模块的 bpf/ 目录一起下发（见 scripts/build-ebpf.sh）。
option(ENTROPY_BUILD_BPF "Build daemon/ebpf/entropy_sched.bpf.c into a loadable object" OFF)
if(ENTROPY_BUILD_BPF)
    find_program(BPF_CLANG NAMES clang-18 clang-17 clang-16 clang-15 clang
                 DOC "clang used to compile the eBPF object")
    if(NOT BPF_CLANG)
        message(FATAL_ERROR "-DENTROPY_BUILD_BPF=ON 但 PATH 上没有 clang")
    endif()
    # -target bpf 不吃 sysroot，所以 libbpf 头必须显式给；缺它就只能报得清楚点
    find_path(BPF_INC_DIR NAMES bpf/bpf_helpers.h
              PATHS /usr/include /usr/local/include /opt/homebrew/include)
    if(NOT BPF_INC_DIR)
        message(FATAL_ERROR
            "找不到 bpf/bpf_helpers.h。Debian/Ubuntu: apt install libbpf-dev；"
            "或 -DBPF_INC_DIR=/path/to/include")
    endif()
    set(BPF_OBJ ${CMAKE_CURRENT_BINARY_DIR}/entropy_sched.bpf.o)
    add_custom_command(OUTPUT ${BPF_OBJ}
        COMMAND ${BPF_CLANG} -O2 -g -target bpf -mcpu=v3
                -D__BPF_TRACING__ -I ${CMAKE_CURRENT_SOURCE_DIR}/src
                -idirafter ${BPF_INC_DIR}
                -c ${CMAKE_CURRENT_SOURCE_DIR}/ebpf/entropy_sched.bpf.c
                -o ${BPF_OBJ}
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/ebpf/entropy_sched.bpf.c
        COMMENT "Compiling eBPF object"
        VERBATIM)
    add_custom_target(entropy_bpf_obj DEPENDS ${BPF_OBJ})
    add_dependencies(entropyd entropy_bpf_obj)
    set_source_files_properties(${BPF_OBJ} PROPERTIES GENERATED TRUE)
    message(STATUS "eBPF object will be built with ${BPF_CLANG} (${BPF_INC_DIR})")
else()
    message(STATUS "eBPF object disabled -> entropyd 运行时使用 schedstat 采样路径")
endif()
