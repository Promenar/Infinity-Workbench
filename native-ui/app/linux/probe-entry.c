#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <limits.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/prctl.h>

int nx_proot_main(int argc, char *const argv[]);

/* 启动即建立独立进程组；记录成功后才进入 proot，供超时和取消清理。 */
int main(int argc, char *const argv[]) {
    if (argc == 2 && strcmp(argv[1], "--shm-helper") == 0) return nx_proot_main(argc, argv);
    if (setsid() < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) return 125;
    if (getppid() == 1) return 125;
    const char *tmp = getenv("PROOT_TMP_DIR");
    char path[PATH_MAX];
    if (!tmp || snprintf(path, sizeof(path), "%s/session.pid", tmp) >= sizeof(path)) return 125;
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return 125;
    int result = dprintf(fd, "%ld\n", (long)getpid());
    close(fd);
    if (result < 0) return 125;
    return nx_proot_main(argc, argv);
}
