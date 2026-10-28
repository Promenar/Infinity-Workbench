package com.promenar.infinity.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 下载固定版本的构建输入并校验 SHA-256。缓存文件摘要一致时不再联网；
 * 摘要不一致的文件不会落到目标路径，避免被后续任务当作可信输入。
 */
public abstract class FetchVerifiedFile extends DefaultTask {
    @Input
    public abstract Property<String> getUrl();

    @Input
    public abstract Property<String> getSha256();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public FetchVerifiedFile() {
        setGroup("build setup");
    }

    @TaskAction
    public void fetch() throws IOException, InterruptedException {
        Path target = getOutputFile().get().getAsFile().toPath();
        String expected = getSha256().get().toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(target)) {
            if (expected.equals(sha256(target))) return;
            getLogger().warn("缓存文件 {} 摘要不符，重新下载。", target.getFileName());
        }
        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(getUrl().get()))
                    .timeout(Duration.ofMinutes(10))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new GradleException("下载 " + getUrl().get() + " 失败：HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = sha256(partial);
            if (!expected.equals(actual)) {
                throw new GradleException("下载内容 SHA-256 不符：" + getUrl().get() + "，期望 " + expected + "，实际 " + actual);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
