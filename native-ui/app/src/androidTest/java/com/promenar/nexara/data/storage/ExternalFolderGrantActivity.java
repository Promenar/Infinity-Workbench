package com.promenar.nexara.data.storage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/**
 * 由提供商所属测试 APK 发出真实跨 UID URI 结果，供目标应用接收可持久化授权。
 * 测试 APK 独立进程不含 Kotlin 标准库，因此夹具使用 Java。
 */
public final class ExternalFolderGrantActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        result.setData(DocumentsContract.buildTreeDocumentUri(getPackageName() + ".externalfoldersfixture", "root"));
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
