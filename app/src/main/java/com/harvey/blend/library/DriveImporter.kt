package com.harvey.blend.library

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Downloads songs from a Google Drive link shared as "Anyone with the link".
 * Works with a folder link (all audio files inside, including sub-folders) or a single file link.
 * No Google sign-in is needed because the files are public.
 */
object DriveImporter {

    data class DriveFile(val id: String, val name: String)

    class DriveException(message: String) : IOException(message)

    fun folderId(link: String): String? =
        Regex("/folders/([\\w-]{10,})").find(link)?.groupValues?.get(1)
            ?: Regex("[?&]id=([\\w-]{10,})").find(link)?.groupValues?.get(1)?.takeIf { "folder" in link }

    fun fileId(link: String): String? =
        Regex("/file/d/([\\w-]{10,})").find(link)?.groupValues?.get(1)
            ?: Regex("[?&]id=([\\w-]{10,})").find(link)?.groupValues?.get(1)

    /** Lists the audio files of a public folder (and its sub-folders, up to [depth] levels). */
    fun listFolder(folderId: String, depth: Int = 2): List<DriveFile> {
        val html = fetchText("https://drive.google.com/embeddedfolderview?id=$folderId#list")
        if ("flip-entry" !in html) {
            throw DriveException("Can't open this Drive folder. In Drive, set sharing to “Anyone with the link”.")
        }
        val out = ArrayList<DriveFile>()
        // Each entry: <div class="flip-entry" id="entry-ID"> … <a href="…"> … <div class="flip-entry-title">NAME</div>
        val entry = Regex("""id="entry-([\w-]+)"[\s\S]*?href="([^"]+)"[\s\S]*?class="flip-entry-title">([\s\S]*?)</div>""")
        for (m in entry.findAll(html)) {
            val id = m.groupValues[1]
            val href = m.groupValues[2]
            val name = unescape(m.groupValues[3].trim())
            if ("/folders/" in href) {
                if (depth > 0) out += listFolder(id, depth - 1)
            } else if (name.substringAfterLast('.', "").lowercase() in LibraryScanner.AUDIO_EXT) {
                out += DriveFile(id, name)
            }
        }
        return out
    }

    /**
     * Downloads one file into [dir] and returns it. The name comes from [name] or, for a single
     * shared file, from Drive's response. Skips files that are already there.
     */
    fun download(fileId: String, dir: File, name: String?, onBytes: (Long) -> Unit = {}): File {
        name?.let { n -> File(dir, safe(n)).takeIf { it.exists() }?.let { return it } }
        val conn = open("https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t")
        try {
            val type = conn.contentType ?: ""
            if (conn.responseCode != 200 || type.startsWith("text/html")) {
                throw DriveException("Drive refused the download (is it shared with “Anyone with the link”?)")
            }
            val headerName = conn.getHeaderField("Content-Disposition")?.let { cd ->
                Regex("filename\\*=UTF-8''([^;]+)").find(cd)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                    ?: Regex("filename=\"([^\"]+)\"").find(cd)?.groupValues?.get(1)
            }
            val target = File(dir, safe(name ?: headerName ?: "$fileId.mp3"))
            val tmp = File(target.path + ".part")
            conn.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
            if (!tmp.renameTo(target)) throw DriveException("Couldn't save ${target.name}")
            return target
        } finally {
            conn.disconnect()
        }
    }

    private fun safe(n: String) = n.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Blend")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                current = URL(current, loc)
            } else return c
        }
        throw DriveException("Too many redirects from Google Drive")
    }

    private fun fetchText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 404) throw DriveException("This Drive link doesn't exist or isn't shared.")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
}
