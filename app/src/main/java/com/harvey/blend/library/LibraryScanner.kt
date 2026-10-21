package com.harvey.blend.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

/** A music file found in the user's folder. */
data class FoundFile(val uri: Uri, val relativePath: String, val name: String, val size: Long, val modified: Long)

/** Walks the folder the user picked (Storage Access Framework) and lists audio files. */
object LibraryScanner {
    val AUDIO_EXT = setOf("mp3", "aif", "aiff", "wav", "flac", "m4a", "aac", "alac", "ogg", "opus")

    fun scan(context: Context, treeUri: Uri, onFound: (Int) -> Unit = {}): List<FoundFile> {
        val out = ArrayList<FoundFile>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<Pair<String, String>>() // documentId to relative dir
        stack.addLast(rootId to "")
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (stack.isNotEmpty()) {
            val (docId, rel) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (name.startsWith(".")) continue
                    val path = if (rel.isEmpty()) name else "$rel/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.addLast(id to path)
                    } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                        out += FoundFile(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id), path, name,
                            if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                        )
                        if (out.size % 25 == 0) onFound(out.size)
                    }
                }
            }
        }
        onFound(out.size)
        return out.sortedBy { it.relativePath.lowercase() }
    }

    /** Stable id: survives rescans and moving the phone's folder, changes if the file changes. */
    fun idFor(relativePath: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$relativePath|$size".toByteArray())
        return md.take(10).joinToString("") { "%02x".format(it) }
    }
}
