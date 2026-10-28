package com.promenar.infinity.buildlogic;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 打包 Python 使用的不压缩 zip：zlib 本身是扩展模块，解释器启动早期尚不可用，压缩条目无法读取；
 * 不压缩也让发行扫描能直接检查条目内容。条目使用固定本地时间，同一输入在任意时区得到相同字节。
 */
final class StoredZip implements AutoCloseable {
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    private final OutputStream file;
    private final ZipOutputStream zip;

    StoredZip(Path target) throws IOException {
        file = Files.newOutputStream(target);
        zip = new ZipOutputStream(file, StandardCharsets.UTF_8);
        zip.setMethod(ZipOutputStream.STORED);
    }

    /** 目录条目以 / 结尾，供命名空间包识别。 */
    void directory(String name) throws IOException {
        put(name.endsWith("/") ? name : name + "/", new byte[0]);
    }

    void put(String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        try (file) {
            zip.close();
        }
    }
}
