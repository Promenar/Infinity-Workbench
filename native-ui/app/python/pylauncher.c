// 官方 Android 构建只提供 libpython（嵌入模式），这里以可执行文件形式包装 Py_BytesMain。
// 以 libnxpython.so 命名放入 jniLibs，安装后位于 nativeLibraryDir，可被 execve；
// PYTHONHOME、PYTHONPATH 与 LD_LIBRARY_PATH 由调用方设置。
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
