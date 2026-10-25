#include <unistd.h>
#include <signal.h>
#include <string.h>
#include <sys/prctl.h>

int nx_proot_main(int argc, char *const argv[]);

/* 进程组由宿主 setsid 建立；主进程退出时终止 tracer，不写共享 pid 文件。 */
int main(int argc, char *const argv[]) {
    if (argc == 2 && strcmp(argv[1], "--shm-helper") == 0) return nx_proot_main(argc, argv);
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent) return 125;
    return nx_proot_main(argc, argv);
}
