package com.promenar.nexara.buildlogic;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Linux 构建输入的读取桥接；发行签名始终由应用构建脚本的环境变量独立配置。 */
public final class LinuxProbeInputs {
    private final Properties properties;

    private LinuxProbeInputs(Properties properties) { this.properties = properties; }

    public static LinuxProbeInputs load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        return new LinuxProbeInputs(properties);
    }

    public String getProperty(String name) { return properties.getProperty(name); }
}
