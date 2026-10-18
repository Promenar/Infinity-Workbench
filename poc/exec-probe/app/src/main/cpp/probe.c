// 执行能力探针：以 lib*.so 名义随 APK 打包，安装时解压到 nativeLibraryDir 后作为可执行文件运行。
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static void print_context(void) {
    char buf[256] = {0};
    int fd = open("/proc/self/attr/current", O_RDONLY);
    if (fd >= 0) {
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        if (n > 0) buf[n] = 0;
        close(fd);
    }
    printf("uid=%d gid=%d euid=%d pid=%d ppid=%d context=%s\n", getuid(), getgid(), geteuid(), getpid(), getppid(), buf);
}

static int cmd_cat(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { printf("cat %s: errno=%d %s\n", path, errno, strerror(errno)); return 1; }
    char buf[128] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    printf("cat %s: ok bytes=%zd head=%.40s\n", path, n, n > 0 ? buf : "");
    return 0;
}

static int cmd_net(const char *ip, int port) {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) { printf("net socket: errno=%d %s\n", errno, strerror(errno)); return 1; }
    struct timeval tv = {5, 0};
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, ip, &addr.sin_addr);
    int r = connect(s, (struct sockaddr *)&addr, sizeof(addr));
    printf("net %s:%d: %s errno=%d %s\n", ip, port, r == 0 ? "connected" : "failed", r == 0 ? 0 : errno, r == 0 ? "" : strerror(errno));
    close(s);
    return r == 0 ? 0 : 1;
}

// 每 5 秒追加一行心跳；busy=1 时在间隔内持续占用 CPU，用于观察后台 CPU 管控。
static int cmd_heartbeat(const char *path, int seconds, int busy) {
    time_t start = time(NULL);
    long n = 0;
    while (time(NULL) - start < seconds) {
        FILE *f = fopen(path, "a");
        if (f) { fprintf(f, "%ld %ld pid=%d\n", (long)time(NULL), n++, getpid()); fclose(f); }
        if (busy) {
            time_t until = time(NULL) + 5;
            volatile unsigned long x = 0;
            while (time(NULL) < until) x++;
        } else {
            sleep(5);
        }
    }
    FILE *f = fopen(path, "a");
    if (f) { fprintf(f, "%ld done\n", (long)time(NULL)); fclose(f); }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2 || strcmp(argv[1], "id") == 0) { print_context(); return 0; }
    if (strcmp(argv[1], "cat") == 0 && argc > 2) return cmd_cat(argv[2]);
    if (strcmp(argv[1], "net") == 0 && argc > 3) return cmd_net(argv[2], atoi(argv[3]));
    if (strcmp(argv[1], "heartbeat") == 0 && argc > 4) return cmd_heartbeat(argv[2], atoi(argv[3]), atoi(argv[4]));
    if (strcmp(argv[1], "exec") == 0 && argc > 2) {
        execv(argv[2], &argv[2]);
        printf("exec %s: errno=%d %s\n", argv[2], errno, strerror(errno));
        return 1;
    }
    printf("unknown command %s\n", argv[1]);
    return 2;
}
