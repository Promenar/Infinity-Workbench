package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import javax.inject.Inject;

/** 只将已校验的原始 rootfs 与版本信息放进生成的资产目录。 */
public abstract class PrepareLinuxRuntimeAssets extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchive();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRuntimeProperties();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction public void prepare() throws IOException {
        Path output = getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (var input = Files.newInputStream(getRuntimeProperties().get().getAsFile().toPath())) { properties.load(input); }
        Path asset = output.resolve(properties.getProperty("alpine.asset")).normalize();
        if (!asset.startsWith(output) || asset.equals(output)) throw new IOException("非法 rootfs 资产路径");
        getFileSystemOperations().delete(spec -> spec.delete(output));
        Files.createDirectories(asset.getParent());
        Path linux = Files.createDirectories(output.resolve("linux"));
        Files.copy(getArchive().get().getAsFile().toPath(), asset, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(getRuntimeProperties().get().getAsFile().toPath(), linux.resolve("linux-runtime.properties"), StandardCopyOption.REPLACE_EXISTING);
    }
}
