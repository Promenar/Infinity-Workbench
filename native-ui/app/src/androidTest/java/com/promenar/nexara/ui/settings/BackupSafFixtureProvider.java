package com.promenar.nexara.ui.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/** 仅为 androidTest 提供 app 外 content:// 文档句柄，不参与生产 APK。 */
public final class BackupSafFixtureProvider extends ContentProvider {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid fixture name");
        }
        if (getContext() == null) {
            throw new IllegalStateException("provider context unavailable");
        }
        File root = new File(getContext().getCacheDir(), "backup-saf-fixtures");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("fixture directory unavailable");
        }
        File file = new File(root, name);
        int flags = mode.indexOf('w') >= 0
                ? ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_READ_WRITE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
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
