package com.harvey.blend.audio

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

object PcmCache {
    private fun dir(context: Context) = File(context.cacheDir, "pcm").apply { mkdirs() }

    fun fileFor(context: Context, trackId: String) = File(dir(context), "$trackId.pcm")

    /** Decodes the track into the cache (if not already there) and returns it, ready to play. */
    fun prepare(context: Context, trackId: String, uri: Uri, cancelled: () -> Boolean = { false }): PcmTrack {
        val f = fileFor(context, trackId)
        if (f.exists() && f.length() > PcmTrack.HEADER) return PcmTrack(f)
        val tmp = File(f.path + ".part")
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
            val bytes = ByteArray(8192 * 4)
            var headerWritten = false
            AudioDecoder.decode(context, uri, object : PcmSink {
                override fun onFormat(sampleRate: Int) {
                    if (headerWritten) return
                    out.write(byteArrayOf((sampleRate ushr 24).toByte(), (sampleRate ushr 16).toByte(), (sampleRate ushr 8).toByte(), sampleRate.toByte(), 0, 0, 0, 2))
                    headerWritten = true
                }
                override fun onPcm(data: ShortArray, frames: Int) {
                    var o = 0
                    val buf = if (bytes.size >= frames * 4) bytes else ByteArray(frames * 4)
                    for (i in 0 until frames * 2) {
                        val v = data[i].toInt()
                        buf[o++] = v.toByte(); buf[o++] = (v shr 8).toByte()
                    }
                    out.write(buf, 0, o)
                }
            }, cancelled)
        }
        tmp.renameTo(f)
        return PcmTrack(f)
    }

    /** Keeps the cache small: deletes everything except the given tracks. */
    fun trim(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".part").removeSuffix(".pcm")
            if (id !in keep) f.delete()
        }
    }
}
