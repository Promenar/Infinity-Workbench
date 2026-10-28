package com.promenar.infinity.buildlogic;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** 随打包 Python 附带的纯 Python wheel 清单，来源为 app/python/python-site.properties。 */
public final class PackagedPythonWheels {
    /** 只接受与平台无关的纯 Python wheel；原生扩展无法从数据目录以外的位置按模块名加载。 */
    private static final Pattern PURE_WHEEL = Pattern.compile("[A-Za-z0-9_.]+-[A-Za-z0-9_.!+]+-py3-none-any\\.whl");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Properties properties;

    private PackagedPythonWheels(Properties properties) {
        this.properties = properties;
    }

    public static PackagedPythonWheels load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new GradleException("无法读取 " + file, error);
        }
        return new PackagedPythonWheels(properties);
    }

    public List<String> getNames() {
        return Arrays.stream(required("wheels").split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    public String url(String name) {
        String url = required(name + ".url");
        if (!url.startsWith("https://")) throw new GradleException(name + ".url 必须使用 https");
        return url;
    }

    public String fileName(String name) {
        String url = url(name);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (!PURE_WHEEL.matcher(fileName).matches()) throw new GradleException(name + " 不是纯 Python wheel：" + fileName);
        return fileName;
    }

    public String sha256(String name) {
        String sha256 = required(name + ".sha256");
        if (!SHA256.matcher(sha256).matches()) throw new GradleException(name + ".sha256 格式无效");
        return sha256;
    }

    private String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new GradleException("python-site.properties 缺少 " + key);
        return value.trim();
    }
}
