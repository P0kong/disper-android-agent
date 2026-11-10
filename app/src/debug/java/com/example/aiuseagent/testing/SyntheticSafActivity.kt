package com.example.aiuseagent.testing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.TextView

/** Debug-only, non-exported synthetic picker host. Never loads application history. */
class SyntheticSafActivity : Activity() {
    var grantedTree: String? = null
        private set
    private lateinit var expected: Uri
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expected=Uri.parse(requireNotNull(intent.getStringExtra("syntheticTree")))
        require(expected.scheme=="content" && expected.authority=="com.android.externalstorage.documents")
        val documentId=DocumentsContract.getTreeDocumentId(expected)
        require(Regex("primary:Download/AAUA_T01_MCP_[a-f0-9-]{36}").matches(documentId))
        status=TextView(this).apply {text="合成 SAF 测试";textSize=22f}
        setContentView(status)
        if(savedInstanceState==null) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri(expected.authority,documentId))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),71)
        }
    }

    @Deprecated("Required for this minimal debug-only picker host")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=71)return
        val result=data?.data
        if(resultCode!=RESULT_OK || result!=expected){status.text="合成目录授权失败";return}
        val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if(flags!=(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)){
            status.text="合成目录授权不足";return
        }
        contentResolver.takePersistableUriPermission(result,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        grantedTree=result.toString();status.text="合成目录授权完成"
    }
}
