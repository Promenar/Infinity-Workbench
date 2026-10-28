package com.promenar.infinity.ui.renderer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仅存在于测试 APK，证明 content 目标存在并记录 WebView 是否触达读取入口。
 * 系统会在测试 APK 自己的进程中创建本提供商，该进程不含 Kotlin 标准库，因此使用 Java。
 */
public final class RichContentDeviceProvider extends ContentProvider {
    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("测试提供商只允许只读");
        reads.incrementAndGet();
        File file = new File(getContext().getCacheDir(), "rich-content-provider.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("content-fixture-readable".getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new FileNotFoundException("无法写入测试内容");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("reset".equals(method)) reads.set(0);
        Bundle result = new Bundle();
        result.putInt("reads", reads.get());
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
