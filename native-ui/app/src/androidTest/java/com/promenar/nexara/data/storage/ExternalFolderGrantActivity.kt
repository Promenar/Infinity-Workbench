package com.promenar.nexara.data.storage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract

/** 由提供商所属测试 APK 发出真实跨 UID URI 结果，供目标应用接收可持久化授权。 */
class ExternalFolderGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = DocumentsContract.buildTreeDocumentUri("$packageName.externalfoldersfixture", "root")
        setResult(RESULT_OK, Intent().apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        })
        finish()
    }
}
