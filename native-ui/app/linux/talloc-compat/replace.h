#pragma once
/* NDK bionic 已提供 talloc 所需的 libc 接口，不引入 Samba 的替代实现。 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
#include <unistd.h>
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define PRINTF_ATTRIBUTE(a, b) __attribute__((format(printf, a, b)))
#define MIN(a, b) ((a) < (b) ? (a) : (b))
/* bionic 没有 Annex K；保留边界检查和不可被优化移除的清零语义。 */
static inline int memset_s(void *destination, size_t capacity, int value, size_t count) {
    if (!destination) return EINVAL;
    if (count > capacity) return ERANGE;
    volatile unsigned char *bytes = destination;
    while (count--) *bytes++ = (unsigned char)value;
    return 0;
}
