package com.harvey.blend.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Receives decoded audio as interleaved 16-bit stereo. */
interface PcmSink {
    fun onFormat(sampleRate: Int)
    /** [data] holds [frames] stereo frames (2 shorts per frame). */
    fun onPcm(data: ShortArray, frames: Int)
}

class DecodeCancelled : Exception()

/**
 * Decodes AIFF / AIFC / WAV directly (Android has no AIFF decoder) and everything else
 * (MP3, FLAC, M4A/AAC, ALAC, OGG…) through MediaCodec.
 */
object AudioDecoder {

    fun decode(context: Context, uri: Uri, sink: PcmSink, cancelled: () -> Boolean = { false }) {
        val header = ByteArray(12)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            var n = 0
            while (n < 12) { val r = ins.read(header, n, 12 - n); if (r < 0) break; n += r }
        } ?: throw IOException("Cannot open file")
        val tag = String(header, 0, 4, Charsets.US_ASCII)
        val form = String(header, 8, 4, Charsets.US_ASCII)
        when {
            tag == "FORM" && (form == "AIFF" || form == "AIFC") -> decodePcmContainer(context, uri, sink, cancelled, aiff = true)
            tag == "RIFF" && form == "WAVE" -> decodePcmContainer(context, uri, sink, cancelled, aiff = false)
            else -> decodeWithMediaCodec(context, uri, sink, cancelled)
        }
    }

    // ---- AIFF / WAV --------------------------------------------------------------------------------

    private class Fmt(val channels: Int, val bits: Int, val rate: Int, val float: Boolean, val bigEndian: Boolean)

    private fun decodePcmContainer(context: Context, uri: Uri, sink: PcmSink, cancelled: () -> Boolean, aiff: Boolean) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
        pfd.use {
            val ins = BufferedInputStream(FileInputStream(pfd.fileDescriptor), 1 shl 16)
            skipFully(ins, 12)
            var fmt: Fmt? = null
            while (true) {
                val id = readId(ins) ?: throw IOException("No audio data found")
                val size = if (aiff) readIntBE(ins).toLong() and 0xffffffffL else readIntLE(ins).toLong() and 0xffffffffL
                when {
                    aiff && id == "COMM" -> {
                        val ch = readShortBE(ins)
                        readIntBE(ins) // frames
                        val bits = readShortBE(ins)
                        val rate = readExtended(ins)
                        var consumed = 18L
                        var float = false
                        var big = true
                        if (size >= 22) {
                            val comp = readId(ins) ?: "NONE"
                            consumed += 4
                            when (comp) {
                                "NONE", "twos" -> {}
                                "sowt" -> big = false
                                "fl32", "FL32" -> float = true
                                else -> throw IOException("Unsupported AIFC compression: $comp")
                            }
                        }
                        skipFully(ins, size - consumed + (size and 1))
                        fmt = Fmt(ch, bits, rate.toInt(), float, big)
                    }
                    aiff && id == "SSND" -> {
                        val f = fmt ?: throw IOException("SSND before COMM")
                        val offset = readIntBE(ins).toLong()
                        readIntBE(ins)
                        skipFully(ins, offset)
                        streamPcm(ins, size - 8 - offset, f, sink, cancelled)
                        return
                    }
                    !aiff && id == "fmt " -> {
                        val audioFormat = readShortLE(ins)
                        val ch = readShortLE(ins)
                        val rate = readIntLE(ins)
                        readIntLE(ins); readShortLE(ins)
                        val bits = readShortLE(ins)
                        var consumed = 16L
                        var formatCode = audioFormat
                        if (audioFormat == 0xFFFE && size >= 40) {
                            readShortLE(ins); readShortLE(ins); readIntLE(ins)
                            formatCode = readShortLE(ins)
                            consumed += 10
                        }
                        skipFully(ins, size - consumed + (size and 1))
                        if (formatCode != 1 && formatCode != 3) throw IOException("Unsupported WAV encoding ($formatCode)")
                        fmt = Fmt(ch, bits, rate, formatCode == 3, false)
                    }
                    !aiff && id == "data" -> {
                        val f = fmt ?: throw IOException("data before fmt")
                        streamPcm(ins, size, f, sink, cancelled)
                        return
                    }
                    else -> skipFully(ins, size + (size and 1))
                }
            }
        }
    }

    private fun streamPcm(ins: InputStream, bytes: Long, f: Fmt, sink: PcmSink, cancelled: () -> Boolean) {
        if (f.channels < 1 || f.bits !in listOf(8, 16, 24, 32)) throw IOException("Unsupported PCM format: ${f.bits} bit")
        sink.onFormat(f.rate)
        val bps = f.bits / 8
        val frameBytes = bps * f.channels
        val framesPerChunk = 4096
        val raw = ByteArray(framesPerChunk * frameBytes)
        val out = ShortArray(framesPerChunk * 2)
        var remaining = if (bytes > 0) bytes else Long.MAX_VALUE
        while (remaining >= frameBytes) {
            if (cancelled()) throw DecodeCancelled()
            val want = minOf(raw.size.toLong(), remaining - remaining % frameBytes).toInt()
            var got = 0
            while (got < want) { val r = ins.read(raw, got, want - got); if (r < 0) break; got += r }
            val frames = got / frameBytes
            if (frames == 0) break
            for (fr in 0 until frames) {
                val base = fr * frameBytes
                val l = sampleAt(raw, base, f)
                val r = if (f.channels > 1) sampleAt(raw, base + bps, f) else l
                out[fr * 2] = l
                out[fr * 2 + 1] = r
            }
            sink.onPcm(out, frames)
            remaining -= frames.toLong() * frameBytes
            if (got < want) break
        }
    }

    private fun sampleAt(b: ByteArray, i: Int, f: Fmt): Short {
        fun u(k: Int) = b[i + k].toInt() and 0xff
        return when (f.bits) {
            8 -> if (f.bigEndian) (b[i].toInt() shl 8).toShort() else ((u(0) - 128) shl 8).toShort() // AIFF 8-bit is signed, WAV unsigned
            16 -> if (f.bigEndian) ((u(0) shl 8) or u(1)).toShort() else ((u(1) shl 8) or u(0)).toShort()
            24 -> if (f.bigEndian) ((u(0) shl 8) or u(1)).toShort() else ((u(2) shl 8) or u(1)).toShort()
            else -> {
                val bits = if (f.bigEndian) (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3)
                else (u(3) shl 24) or (u(2) shl 16) or (u(1) shl 8) or u(0)
                if (f.float) {
                    (java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                } else (bits shr 16).toShort()
            }
        }
    }

    private fun readId(ins: InputStream): String? {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) { val r = ins.read(b, n, 4 - n); if (r < 0) return null; n += r }
        return String(b, Charsets.US_ASCII)
    }

    private fun readByte(ins: InputStream): Int { val v = ins.read(); if (v < 0) throw EOFException(); return v }
    private fun readIntBE(ins: InputStream) = (readByte(ins) shl 24) or (readByte(ins) shl 16) or (readByte(ins) shl 8) or readByte(ins)
    private fun readIntLE(ins: InputStream): Int { val a = readByte(ins); val b = readByte(ins); val c = readByte(ins); val d = readByte(ins); return a or (b shl 8) or (c shl 16) or (d shl 24) }
    private fun readShortBE(ins: InputStream) = (readByte(ins) shl 8) or readByte(ins)
    private fun readShortLE(ins: InputStream): Int { val a = readByte(ins); val b = readByte(ins); return a or (b shl 8) }

    /** 80-bit IEEE 754 extended, used for the AIFF sample rate. */
    private fun readExtended(ins: InputStream): Double {
        val e = readShortBE(ins)
        var mant = 0L
        repeat(8) { mant = (mant shl 8) or readByte(ins).toLong() }
        val sign = if (e and 0x8000 != 0) -1 else 1
        val exp = (e and 0x7fff) - 16383 - 63
        return sign * (mant ushr 1).toDouble() * 2.0 * 2.0.pow(exp)
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) { if (ins.read() < 0) throw EOFException(); left-- } else left -= s
        }
    }

    // ---- MediaCodec ----------------------------------------------------------------------------------

    private fun decodeWithMediaCodec(context: Context, uri: Uri, sink: PcmSink, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var formatSent = false
                var out = ShortArray(8192)
                while (true) {
                    if (cancelled()) throw DecodeCancelled()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        sink.onFormat(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        formatSent = true
                    } else if (outIdx >= 0) {
                        if (!formatSent) {
                            sink.onFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)); formatSent = true
                        }
                        val buf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            if (out.size < frames * 2) out = ShortArray(frames * 2)
                            for (i in 0 until frames) {
                                val l = fb.get(i * channels)
                                val r = if (channels > 1) fb.get(i * channels + 1) else l
                                out[i * 2] = (l.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                out[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                            }
                            sink.onPcm(out, frames)
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            if (out.size < frames * 2) out = ShortArray(frames * 2)
                            for (i in 0 until frames) {
                                val l = sb.get(i * channels)
                                out[i * 2] = l
                                out[i * 2 + 1] = if (channels > 1) sb.get(i * channels + 1) else l
                            }
                            sink.onPcm(out, frames)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}

/** Collects mono audio at the analysis sample rate (box-filtered downsampling). */
class AnalysisSink(private val targetRate: Int) : PcmSink {
    private var step = 1.0
    private var acc = 0.0
    private var accN = 0
    private var phase = 0.0
    private var buf = FloatArray(1 shl 20)
    var size = 0
        private set

    override fun onFormat(sampleRate: Int) { step = sampleRate.toDouble() / targetRate }

    override fun onPcm(data: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            acc += (data[i * 2] + data[i * 2 + 1]) / 65536.0
            accN++
            phase += 1.0
            if (phase >= step) {
                phase -= step
                if (size == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[size++] = (acc / accN).toFloat()
                acc = 0.0; accN = 0
            }
        }
    }

    fun samples(): FloatArray = buf.copyOf(size)
}
