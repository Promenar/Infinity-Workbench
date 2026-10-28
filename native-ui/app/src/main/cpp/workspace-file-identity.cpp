#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>

namespace {
constexpr const char* kIdentityAttribute = "user.infinity_creation_identity";
constexpr const char* kRootIdentityAttribute = "user.infinity_root_identity";
constexpr size_t kTokenBytes = 36;

void fail(JNIEnv* env, const char* action, int error) {
    char message[160];
    std::snprintf(message, sizeof(message), "%s失败(errno=%d)，未降低文件身份保证", action, error);
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message);
}

class OwnedFile {
public:
    explicit OwnedFile(int borrowed) : fd_(fcntl(borrowed, F_DUPFD_CLOEXEC, 0)) {}
    ~OwnedFile() { if (fd_ >= 0) close(fd_); }
    int get() const { return fd_; }
    bool regular() const { struct stat state{}; return fd_ >= 0 && fstat(fd_, &state) == 0 && S_ISREG(state.st_mode); }
    bool directory() const { struct stat state{}; return fd_ >= 0 && fstat(fd_, &state) == 0 && S_ISDIR(state.st_mode); }
private:
    int fd_;
};

bool fileName(JNIEnv* env, jbyteArray input, std::string& output) {
    if (input == nullptr) return false;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > 255) return false;
    output.resize(length);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(&output[0]));
    if (env->ExceptionCheck()) return false;
    return output != "." && output != ".." && output.find('/') == std::string::npos &&
        output.find('\\') == std::string::npos && output.find('\0') == std::string::npos;
}

void createIdentity(JNIEnv* env, jint borrowedFd, jstring token, const char* attribute, bool root, int flags) {
    OwnedFile file(borrowedFd);
    if ((root && !file.directory()) || (!root && !file.regular() && !file.directory())) {
        fail(env, "打开文件或目录身份FD", errno ? errno : EINVAL); return;
    }
    if (token == nullptr || env->GetStringUTFLength(token) != kTokenBytes) { fail(env, "校验身份令牌", EINVAL); return; }
    const char* bytes = env->GetStringUTFChars(token, nullptr);
    if (bytes == nullptr) return;
    const int result = fsetxattr(file.get(), attribute, bytes, kTokenBytes, flags);
    const int error = errno;
    env->ReleaseStringUTFChars(token, bytes);
    if (result != 0) { fail(env, "持久化身份属性", error); return; }
    if (fsync(file.get()) != 0) fail(env, "同步身份属性", errno);
}

jstring readIdentity(JNIEnv* env, jint borrowedFd, const char* attribute, bool root) {
    OwnedFile file(borrowedFd);
    if ((root && !file.directory()) || (!root && !file.regular() && !file.directory())) {
        fail(env, "打开文件或目录身份FD", errno ? errno : EINVAL); return nullptr;
    }
    char bytes[kTokenBytes + 1]{};
    const ssize_t count = fgetxattr(file.get(), attribute, bytes, kTokenBytes);
    if (count < 0 && errno == ENODATA) return nullptr;
    if (count < 0 && (errno == ENOTSUP || errno == EOPNOTSUPP)) {
        jclass type = env->FindClass("java/lang/UnsupportedOperationException");
        if (type != nullptr) env->ThrowNew(type, "文件系统不支持目录FD身份属性");
        return nullptr;
    }
    if (count < 0) { fail(env, "读取身份属性", errno); return nullptr; }
    if (count != kTokenBytes) { fail(env, "校验身份属性长度", EINVAL); return nullptr; }
    for (size_t index = 0; index < kTokenBytes; ++index) {
        const char value = bytes[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') || value == '-')) {
            fail(env, "校验身份属性内容", EINVAL); return nullptr;
        }
    }
    if (root && fsync(file.get()) != 0) { fail(env, "同步根身份属性", errno); return nullptr; }
    return env->NewStringUTF(bytes);
}

jstring readCompatibleIdentity(JNIEnv* env, jint fd, const char* current, const char* legacy, bool root) {
    jstring value = readIdentity(env, fd, current, root);
    if (env->ExceptionCheck()) return nullptr;
    jstring old = readIdentity(env, fd, legacy, root);
    if (env->ExceptionCheck()) return nullptr;
    if (value != nullptr && old != nullptr) {
        const char* left = env->GetStringUTFChars(value, nullptr);
        if (left == nullptr) return nullptr;
        const char* right = env->GetStringUTFChars(old, nullptr);
        if (right == nullptr) { env->ReleaseStringUTFChars(value, left); return nullptr; }
        const bool same = std::strcmp(left, right) == 0;
        env->ReleaseStringUTFChars(value, left);
        env->ReleaseStringUTFChars(old, right);
        if (!same) { fail(env, "校验新旧身份属性一致性", EINVAL); return nullptr; }
    }
    return value != nullptr ? value : old;
}

}

extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileMoves_nativeMoveNoReplace(
        JNIEnv* env, jobject, jint sourceFd, jbyteArray sourceName, jint targetFd, jbyteArray targetName) {
    OwnedFile source(sourceFd), target(targetFd);
    if (!source.directory() || !target.directory()) { fail(env, "打开移动目录FD", errno ? errno : EINVAL); return; }
    std::string from, to;
    if (!fileName(env, sourceName, from) || !fileName(env, targetName, to)) {
        if (!env->ExceptionCheck()) fail(env, "校验移动文件名", EINVAL);
        return;
    }
    if (renameat2(source.get(), from.c_str(), target.get(), to.c_str(), RENAME_NOREPLACE) != 0) {
        const int error = errno;
        if (error == EEXIST || error == ENOTEMPTY) {
            jclass type = env->FindClass("java/nio/file/FileAlreadyExistsException");
            if (type != nullptr) env->ThrowNew(type, "工作区移动目标已存在，两个节点均保留");
        } else if (error == ENOENT) {
            jclass type = env->FindClass("java/nio/file/NoSuchFileException");
            if (type != nullptr) env->ThrowNew(type, "工作区移动源或父目录不存在");
        } else fail(env, "不覆盖移动", error);
        return;
    }
    if (fsync(source.get()) != 0) { fail(env, "同步移动源目录", errno); return; }
    if (fsync(target.get()) != 0) fail(env, "同步移动目标目录", errno);
}

extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeCreate(
        JNIEnv* env, jobject, jint borrowedFd, jstring token) {
    createIdentity(env, borrowedFd, token, kIdentityAttribute, false, XATTR_CREATE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeRead(
        JNIEnv* env, jobject, jint borrowedFd) {
    return readCompatibleIdentity(env, borrowedFd, kIdentityAttribute, "user.nexara_creation_identity", false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeCreateRoot(
        JNIEnv* env, jobject, jint borrowedFd, jstring token) {
    createIdentity(env, borrowedFd, token, kRootIdentityAttribute, true, XATTR_CREATE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeReadRoot(
        JNIEnv* env, jobject, jint borrowedFd) {
    return readCompatibleIdentity(env, borrowedFd, kRootIdentityAttribute, "user.nexara_root_identity", true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeRestoreRoot(
        JNIEnv* env, jobject, jint borrowedFd, jstring token) {
    createIdentity(env, borrowedFd, token, kRootIdentityAttribute, true, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeCreatePersistent(
        JNIEnv* env, jobject, jint borrowedFd, jstring token) {
    createIdentity(env, borrowedFd, token, "user.infinity_persistent_identity", false, XATTR_CREATE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_promenar_infinity_data_repository_AndroidWorkspaceFileIdentity_nativeReadPersistent(
        JNIEnv* env, jobject, jint borrowedFd) {
    return readCompatibleIdentity(env, borrowedFd, "user.infinity_persistent_identity", "user.nexara_persistent_identity", false);
}
