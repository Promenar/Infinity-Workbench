// python 启动器：官方 Android 构建只提供 libpython（嵌入模式），这里以可执行文件形式包装 Py_BytesMain。
// 以 libnxpython.so 命名随 APK 打包；PYTHONHOME 与 LD_LIBRARY_PATH 由调用方设置。
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
