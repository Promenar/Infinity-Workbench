cmake_minimum_required(VERSION 3.22.1)
project(nexara_linux_probe C ASM)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "探针只支持 arm64-v8a")
endif()
set(SRC "${PROOT_SOURCE}/src")
add_compile_options(-O2 -g0 -fPIE -fno-ident
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=linux"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
    "-ffile-prefix-map=${PROOT_SOURCE}=proot"
    "-ffile-prefix-map=${TALLOC_SOURCE}=talloc")
add_compile_definitions(_GNU_SOURCE _FILE_OFFSET_BITS=64)
include_directories("${SRC}" "${TALLOC_SOURCE}" "${CMAKE_BINARY_DIR}")
include(CheckCSourceCompiles)
check_c_source_compiles("#define _GNU_SOURCE\n#include <sys/uio.h>\nint main(void) { return process_vm_readv(0,0,0,0,0,0); }" HAVE_PROCESS_VM)
check_c_source_compiles("#include <linux/seccomp.h>\n#include <linux/filter.h>\n#include <sys/prctl.h>\nint main(void) { return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,0); }" HAVE_SECCOMP_FILTER)
configure_file(build.h.in build.h)

# 从固定归档自己的构建清单取源文件，避免遗漏系统调用兼容扩展。
file(READ "${SRC}/GNUmakefile" MAKEFILE)
string(FIND "${MAKEFILE}" "OBJECTS +=" OBJECT_START)
string(FIND "${MAKEFILE}" "\ndefine define_from_arch.h" OBJECT_END)
math(EXPR OBJECT_LENGTH "${OBJECT_END} - ${OBJECT_START}")
string(SUBSTRING "${MAKEFILE}" ${OBJECT_START} ${OBJECT_LENGTH} OBJECT_BLOCK)
string(REGEX MATCHALL "[-a-z_0-9/]+\\.o" OBJECTS "${OBJECT_BLOCK}")
list(LENGTH OBJECTS SOURCE_COUNT)
if(NOT SOURCE_COUNT EQUAL 66)
    message(FATAL_ERROR "固定 proot 提交的源码清单应包含 66 项，实际 ${SOURCE_COUNT}")
endif()
set(PROOT_SOURCES)
foreach(OBJECT IN LISTS OBJECTS)
    string(REGEX REPLACE "\\.o$" ".c" SOURCE "${OBJECT}")
    list(APPEND PROOT_SOURCES "${SRC}/${SOURCE}")
endforeach()

# loader 不依赖 libc 或动态链接器，位置无关且不产生运行时重定位。
add_executable(nxloader "${SRC}/loader/loader.c" "${SRC}/loader/assembly.S")
target_compile_options(nxloader PRIVATE -ffreestanding -fno-builtin -fno-stack-protector -fvisibility=hidden)
target_link_options(nxloader PRIVATE -nostdlib -pie -Wl,--as-needed,--no-dynamic-linker,-e,_start,--undefined=pokedata_workaround,--build-id=none,-z,noexecstack,-z,max-page-size=16384)
set_target_properties(nxloader PROPERTIES OUTPUT_NAME libnxproot-loader.so)
add_custom_command(OUTPUT "${CMAKE_BINARY_DIR}/loader-info.c"
    COMMAND "${CMAKE_COMMAND}" "-DNM=${CMAKE_NM}" "-DLOADER=$<TARGET_FILE:nxloader>"
        "-DOUTPUT=${CMAKE_BINARY_DIR}/loader-info.c" -P "${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake"
    DEPENDS nxloader "${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake")

add_library(talloc_probe OBJECT "${TALLOC_SOURCE}/talloc.c")
target_include_directories(talloc_probe BEFORE PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/talloc-compat")
target_compile_definitions(talloc_probe PRIVATE TALLOC_BUILD_VERSION_MAJOR=2 TALLOC_BUILD_VERSION_MINOR=4 TALLOC_BUILD_VERSION_RELEASE=4)
# 只重命名上游入口；包装入口建立独立会话并记录进程组，Java 仍直接 exec 打包 proot。
set_source_files_properties("${SRC}/cli/cli.c" PROPERTIES COMPILE_DEFINITIONS "main=nx_proot_main")
# 固定上游文件使用 strcmp/memset 却未包含声明，按文件补头而不放宽编译诊断。
set_source_files_properties("${SRC}/extension/ashmem_memfd/ashmem_memfd.c" PROPERTIES COMPILE_OPTIONS "-include;string.h")
add_executable(nxproot ${PROOT_SOURCES} "${CMAKE_BINARY_DIR}/loader-info.c" probe-entry.c $<TARGET_OBJECTS:talloc_probe>)
target_compile_definitions(nxproot PRIVATE "PROOT_UNBUNDLE_LOADER=\"/unavailable\"")
target_link_libraries(nxproot PRIVATE dl)
target_link_options(nxproot PRIVATE -pie -Wl,--build-id=none,-z,noexecstack,-z,max-page-size=16384)
set_target_properties(nxproot PROPERTIES OUTPUT_NAME libnxproot.so)
