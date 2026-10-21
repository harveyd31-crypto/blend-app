package com.harvey.blend.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.harvey.blend.model.TransitionStyle
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs [MixCore] on a high-priority audio thread feeding an AudioTrack. Commands from the UI are
 * queued and applied between blocks so the mixer never sees half-updated state.
 */
class MixEngine(onMasterChanged: (String) -> Unit, onEnded: () -> Unit) {
    private val core = MixCore(onMasterChanged, onEnded)
    val state: StateFlow<MixState> = core.state

    private val commands = ConcurrentLinkedQueue<MixCore.() -> Unit>()
    private val lock = Object()
    @Volatile private var running = true
    private var thread: Thread? = null

    var style: TransitionStyle
        get() = core.style
        set(v) { core.style = v }
    var bars: Int
        get() = core.bars
        set(v) { core.bars = v }

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "BlendMixer").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.join(500)
        thread = null
    }

    private fun post(cmd: MixCore.() -> Unit) {
        commands.add(cmd)
        synchronized(lock) { lock.notifyAll() }
    }

    fun play(t: DeckTrack, pcm: PcmTrack, fromSec: Double = 0.0) = post { play(t, pcm, fromSec) }
    fun setNext(t: DeckTrack?, pcm: PcmTrack?) = post { setNext(t, pcm) }
    fun pause() = post { pause() }
    fun resume() = post { resume() }
    fun togglePlay() = post { togglePlay() }
    fun seek(sec: Double) = post { seek(sec) }
    fun mixNow() = post { mixNow() }
    fun skip() = post { skip() }
    fun stop() = post { stop() }
    /** Re-plan the next transition after the transition length or cue points changed. */
    fun replan(t: DeckTrack?) = post { updateMaster(t) }

    private fun loop() {
        val rate = core.outRate
        val block = core.block
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, block * 2 * 4 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val out = FloatArray(block * 2)
        var publishCounter = 0
        var started = false
        try {
            while (running) {
                while (true) { val c = commands.poll() ?: break; core.c() }
                if (!core.playing) {
                    if (started) { at.pause(); started = false }
                    core.publish()
                    synchronized(lock) { if (commands.isEmpty() && running) lock.wait(100) }
                    continue
                }
                if (!started) { at.play(); started = true }
                java.util.Arrays.fill(out, 0f)
                core.renderBlock(out)
                at.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (++publishCounter >= 6) { publishCounter = 0; core.publish() }
            }
        } finally {
            runCatching { at.stop() }
            at.release()
        }
    }
}
