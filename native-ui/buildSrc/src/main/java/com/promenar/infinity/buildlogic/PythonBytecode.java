package com.promenar.infinity.buildlogic;

import org.gradle.api.GradleException;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建期字节码：打包 Python 的标准库与 site zip 不带 pyc 时，每次启动解释器都要重新编译导入的模块
 * （真机上 dulwich 一次导入约 1 秒），因此用 magic 与打包 CPython 一致的宿主解释器预编译。
 * 编译放在构建期而不是首次运行时写入数据目录：数据目录可被命令工具改写，免审批运行的 Git 检查会加载被篡改的字节码。
 */
final class PythonBytecode {
    static final String DISABLE_HINT = "可用 -Pinfinity.hostPython=<python 路径> 指定宿主解释器，"
            + "或以 -Pinfinity.pythonBytecode=false 跳过预编译（内置 Python 每次启动重新编译，git 与 Git 面板明显变慢）";

    private static final int MAGIC_MISMATCH = 3;
    private static final Pattern MAGIC_DEFINE = Pattern.compile("(?m)^#define\\s+PYC_MAGIC_NUMBER\\s+(\\d+)\\s*$");

    private PythonBytecode() {
    }

    /** 读取官方包头文件中的 PYC_MAGIC_NUMBER，确认与 python-android.properties 登记的一致。 */
    static void verifyArchiveMagic(Path prefix, String majorMinor, int expected) throws IOException {
        Path header = prefix.resolve("include").resolve("python" + majorMinor).resolve("internal").resolve("pycore_magic_number.h");
        if (!Files.isRegularFile(header)) throw new GradleException("Python 包结构不符：缺少 " + prefix.relativize(header));
        Matcher matcher = MAGIC_DEFINE.matcher(Files.readString(header, StandardCharsets.UTF_8));
        if (!matcher.find()) throw new GradleException("无法从 " + header.getFileName() + " 读取 PYC_MAGIC_NUMBER");
        int actual = Integer.parseInt(matcher.group(1));
        if (actual != expected) {
            throw new GradleException("python-android.properties 的 pycMagic=" + expected + " 与官方包的 PYC_MAGIC_NUMBER " + actual
                    + " 不一致；升级 Python 时同步更新 pycMagic");
        }
    }

    /** 在 [root] 下为每个 .py 生成同目录 .pyc，返回编译摘要（含跳过的文件）；宿主解释器缺失或 magic 不一致时构建失败。 */
    static String compileTree(ExecOperations exec, String hostPython, File compiler, int magic, Path root) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExecResult result;
        try {
            result = exec.exec(spec -> {
                spec.setExecutable(hostPython);
                spec.args("-I", compiler.getPath(), "--magic", Integer.toString(magic), root.toString());
                spec.setStandardOutput(output);
                spec.setErrorOutput(output);
                spec.setIgnoreExitValue(true);
            });
        } catch (RuntimeException failure) {
            throw new GradleException("无法运行宿主 Python「" + hostPython + "」预编译打包 Python 字节码；" + DISABLE_HINT, failure);
        }
        String log = output.toString(StandardCharsets.UTF_8).trim();
        if (result.getExitValue() == MAGIC_MISMATCH) throw new GradleException(log + "；" + DISABLE_HINT);
        if (result.getExitValue() != 0) {
            throw new GradleException("预编译打包 Python 字节码失败（退出码 " + result.getExitValue() + "）：" + log);
        }
        return log;
    }
}
