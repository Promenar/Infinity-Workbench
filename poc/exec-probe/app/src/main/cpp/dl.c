// dlopen 探针：验证从应用数据目录加载原生库（对应 pip 原生扩展）是否被 W^X 策略拒绝。
#include <jni.h>

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}
