package com.example.aiuseagent.platform

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.aiuseagent.data.DirectoryAccess
import com.example.aiuseagent.data.FolderGrant
import com.example.aiuseagent.data.SavedWorkFolder
import com.example.aiuseagent.data.WorkFolderStore

class AndroidDirectoryAccess(private val resolver: ContentResolver) : DirectoryAccess {
    override fun take(uri: String, flags: Int) {
        val tree = uri.toUri()
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(modes and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        resolver.takePersistableUriPermission(tree, modes)
    }

    override fun grant(uri: String): FolderGrant? =
        resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri }?.let {
            FolderGrant(it.isReadPermission, it.isWritePermission)
        }

    override fun displayName(uri: String): String? {
        val tree = uri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // 仅查询用户所选目录自身的显示名称；从不查询 children、不打开内容流。
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override fun release(uri: String) {
        val grant = grant(uri) ?: return
        val modes = (if (grant.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (modes != 0) resolver.releasePersistableUriPermission(uri.toUri(), modes)
    }
}

class PreferenceWorkFolderStore(private val prefs: SharedPreferences) : WorkFolderStore {
    override fun read(): SavedWorkFolder? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return SavedWorkFolder(uri, prefs.getString(KEY_NAME, null) ?: "已选目录")
    }

    override fun save(folder: SavedWorkFolder?) {
        prefs.edit {
            if (folder == null) { remove(KEY_URI); remove(KEY_NAME) }
            else { putString(KEY_URI, folder.uri); putString(KEY_NAME, folder.name) }
        }
    }

    companion object {
        const val KEY_URI = "work_folder_uri"
        const val KEY_NAME = "work_folder_name"
    }
}
