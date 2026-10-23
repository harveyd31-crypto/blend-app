package com.harvey.blend.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

/** A music file found in the user's folder. */
data class FoundFile(val uri: Uri, val relativePath: String, val name: String, val size: Long, val modified: Long)

/** Result of a folder scan: the audio files plus how many other files were seen. */
data class ScanResult(val audio: List<FoundFile>, val otherFiles: Int, val folders: Int)

/** Walks the folder the user picked (Storage Access Framework) and lists audio files. */
object LibraryScanner {
    val AUDIO_EXT = setOf("mp3", "aif", "aiff", "wav", "flac", "m4a", "aac", "alac", "ogg", "opus")

    fun scan(context: Context, treeUri: Uri, onFound: (Int) -> Unit = {}): ScanResult {
        val out = ArrayList<FoundFile>()
        var other = 0
        var folders = 0
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
            val cursor = context.contentResolver.query(children, cols, null, null, null)
                ?: throw IllegalStateException("Can't read this folder — choose it again")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (name.startsWith(".")) continue
                    val path = if (rel.isEmpty()) name else "$rel/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders++
                        stack.addLast(id to path)
                    } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXT || mime.startsWith("audio/")) {
                        out += FoundFile(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id), path, name,
                            if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                        )
                        if (out.size % 25 == 0) onFound(out.size)
                    } else other++
                }
            }
        }
        onFound(out.size)
        return ScanResult(out.sortedBy { it.relativePath.lowercase() }, other, folders)
    }

    /** Songs imported with the Import button, copied into the app's own storage. */
    fun importDir(context: Context) = java.io.File(context.filesDir, "music").apply { mkdirs() }

    fun scanImported(context: Context): List<FoundFile> =
        importDir(context).walkTopDown().filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }.map { f ->
            FoundFile(Uri.fromFile(f), "Imported/" + f.relativeTo(importDir(context)).path, f.name, f.length(), f.lastModified())
        }.sortedBy { it.relativePath.lowercase() }.toList()

    /** Stable id: survives rescans and moving the phone's folder, changes if the file changes. */
    fun idFor(relativePath: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$relativePath|$size".toByteArray())
        return md.take(10).joinToString("") { "%02x".format(it) }
    }
}
