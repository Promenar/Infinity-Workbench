package com.promenar.nexara.buildlogic;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 打包 Python 的版本、下载地址与各架构 SHA-256，来源为 app/python/python-android.properties。 */
public final class PackagedPythonRelease {
    private final Properties properties;

    private PackagedPythonRelease(Properties properties) {
        this.properties = properties;
    }

    public static PackagedPythonRelease load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new GradleException("无法读取 " + file, error);
        }
        return new PackagedPythonRelease(properties);
    }

    public String getVersion() {
        return required("version");
    }

    /** 主次版本，例如 3.14。 */
    public String getMajorMinor() {
        String[] parts = getVersion().split("\\.");
        if (parts.length < 2) throw new GradleException("Python 版本格式无效：" + getVersion());
        return parts[0] + "." + parts[1];
    }

    public String archiveName(String arch) {
        return "python-" + getVersion() + "-" + arch + "-linux-android.tar.gz";
    }

    public String archiveUrl(String arch) {
        return required("baseUrl") + archiveName(arch);
    }

    public String sha256(String arch) {
        return required("sha256." + arch);
    }

    private String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new GradleException("python-android.properties 缺少 " + key);
        return value.trim();
    }
}
