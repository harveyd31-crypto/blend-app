package com.harvey.blend.audio

import com.harvey.blend.model.TransitionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tanh

/** What the mixer needs to know about a track's timing. Times in seconds of the original audio. */
data class DeckTrack(
    val id: String,
    val bpm: Double?,
    val gridStart: Double,
    val mixIn: Double,
    val mixOut: Double,
)

data class MixState(
    val playing: Boolean = false,
    val masterId: String? = null,
    val masterPos: Double = 0.0,
    val masterDuration: Double = 0.0,
    val masterRate: Double = 1.0,
    val incomingId: String? = null,
    val incomingPos: Double = 0.0,
    val incomingDuration: Double = 0.0,
    /** 0–1 while a transition runs, null otherwise. */
    val transitionProgress: Double? = null,
    /** Where (in master seconds) the next transition will start. */
    val transitionStart: Double? = null,
    val transitionLength: Double = 0.0,
    val nextReady: Boolean = false,
    val nextId: String? = null,
)

/**
 * Two-deck mixer core (no Android audio APIs, so it can be tested offline). The outgoing track (master) is the clock: a transition starts exactly on its
 * mix-out downbeat, the incoming track starts on its mix-in downbeat, tempo-matched, and the
 * EQ moves (bass swap) happen on bar lines of the master's grid.
 */
class MixCore(
    /** Called on the audio thread when the incoming track takes over. */
    private val onMasterChanged: (String) -> Unit,
    /** Called when the master track ends with nothing queued. */
    private val onEnded: () -> Unit,
) {
    val outRate = 44100
    val block = 512

    private class Deck {
        var track: DeckTrack? = null
        var pcm: PcmTrack? = null
        var pos = 0.0          // source frame
        var rate = 1.0         // tempo multiplier
        var rateTarget = 1.0
        var rateStep = 0.0
        var gain = 1f
        var low = 1f
        var dry = 1f
        var echoSend = 0f
        val lpL = Biquad(); val lpR = Biquad()
        val echo = Echo()
        var active = false

        fun srcRate() = pcm?.sampleRate ?: 44100
        fun posSec() = pos / srcRate()
        fun durationSec() = pcm?.durationSec ?: 0.0
        fun reset() {
            gain = 1f; low = 1f; dry = 1f; echoSend = 0f; rate = 1.0; rateTarget = 1.0; rateStep = 0.0
            lpL.reset(); lpR.reset(); echo.reset()
        }
    }

    private var a = Deck()   // master
    private var b = Deck()   // incoming

    // Next track waiting to be mixed in.
    private var nextTrack: DeckTrack? = null
    private var nextPcm: PcmTrack? = null

    // Transition plan, in master source seconds.
    private var transStart: Double? = null
    private var transLenSec = 0.0
    private var transBars = 8
    private var inTransition = false
    private var beatMatched = true
    @Volatile var style: TransitionStyle = TransitionStyle.BASS_SWAP
    @Volatile var bars: Int = 8

    private val _state = MutableStateFlow(MixState())
    val state: StateFlow<MixState> = _state

    var playing = false
        private set

    // ---- Commands (run on the audio thread via MixEngine) -------------------------------------------

    fun play(t: DeckTrack, pcm: PcmTrack, fromSec: Double = 0.0) {
        a.reset(); a.track = t; a.pcm = pcm; a.pos = fromSec * pcm.sampleRate; a.active = true
        b.active = false; b.track = null; b.pcm = null
        inTransition = false
        planTransition()
        playing = true
    }

    fun setNext(t: DeckTrack?, pcm: PcmTrack?) {
        if (inTransition) return
        nextTrack = t; nextPcm = pcm
        planTransition()
    }

    fun pause() { playing = false }
    fun resume() { if (a.active) playing = true }
    fun togglePlay() { playing = !playing && a.active }

    fun seek(sec: Double) {
        if (inTransition) finishTransition()
        a.pos = sec * a.srcRate()
        planTransition()
    }

    /** Start the transition at the master's next bar line. */
    fun mixNow() {
        val t = a.track ?: return
        if (inTransition || nextTrack == null || nextPcm == null) return
        val bar = 240.0 / (t.bpm ?: 120.0)
        val now = a.posSec()
        transStart = t.gridStart + ceil((now - t.gridStart) / bar + 0.05) * bar
    }

    /** Hard cut to the next track (from its mix-in). */
    fun skip() {
        val nt = nextTrack ?: return
        val np = nextPcm ?: return
        a.reset(); a.track = nt; a.pcm = np; a.pos = nt.mixIn * np.sampleRate; a.active = true
        b.active = false
        nextTrack = null; nextPcm = null; inTransition = false
        planTransition()
        onMasterChanged(nt.id)
    }

    /** The master track's cues or tempo were edited: re-plan with the new values. */
    fun updateMaster(t: DeckTrack?) {
        if (t != null && a.track?.id == t.id) a.track = t
        if (!inTransition) planTransition()
    }

    fun stop() {
        playing = false; a.active = false; b.active = false; inTransition = false
        a.track = null; a.pcm = null; nextTrack = null; nextPcm = null
    }

    // ---- Audio thread --------------------------------------------------------------------------------

    private fun planTransition() {
        val t = a.track
        val nt = nextTrack
        if (t == null || nt == null || nextPcm == null) { transStart = null; return }
        val bpmA = t.bpm ?: 120.0
        val bar = 240.0 / bpmA
        transBars = bars
        transLenSec = transBars * bar
        var start = t.mixOut
        val now = a.posSec()
        if (start < now + 0.5) start = t.gridStart + ceil((now - t.gridStart) / bar + 0.05) * bar
        // Leave room for the whole transition before the track ends.
        val latest = a.durationSec() - transLenSec
        if (start > latest && latest > now) start = t.gridStart + floor((latest - t.gridStart) / bar) * bar
        transStart = start
    }

    private fun beginTransition() {
        val nt = nextTrack ?: return
        val np = nextPcm ?: return
        b.reset(); b.track = nt; b.pcm = np; b.pos = nt.mixIn * np.sampleRate; b.active = true
        val bpmA = (a.track?.bpm ?: 120.0) * a.rate
        val bpmB = nt.bpm
        beatMatched = false
        if (bpmB != null) {
            val f = listOf(0.5, 1.0, 2.0).minBy { abs(ln(bpmA / (bpmB * it))) }
            val r = bpmA / (bpmB * f)
            if (abs(r - 1) <= 0.08) { b.rate = r; beatMatched = true }
        }
        b.rateTarget = b.rate
        nextTrack = null; nextPcm = null
        inTransition = true
    }

    private fun finishTransition() {
        if (!b.active) { inTransition = false; return }
        val old = a
        a = b; b = old
        b.active = false; b.track = null; b.pcm = null
        a.gain = 1f; a.low = 1f; a.dry = 1f; a.echoSend = 0f
        // Glide back to the track's own tempo over 16 bars.
        a.rateTarget = 1.0
        val glideSec = 16 * 240.0 / (a.track?.bpm ?: 120.0)
        a.rateStep = abs(a.rate - 1.0) / (glideSec * outRate / block)
        inTransition = false
        transStart = null
        a.track?.let { onMasterChanged(it.id) }
        planTransition()
    }

    /** Sets deck parameters for transition progress [p] (0–1). */
    private fun automation(p: Double) {
        val swapP = (transBars / 2).toDouble() / transBars
        val beatP = 1.0 / (transBars * 4)
        fun ramp(x: Double, from: Double, to: Double) = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        fun ease(x: Double) = (0.5 - 0.5 * cos(PI * x))
        val bIn = ease(ramp(p, 0.0, 0.25)).toFloat()
        when (if (beatMatched) style else TransitionStyle.FILTER_FADE) {
            TransitionStyle.BASS_SWAP -> {
                val swap = ease(ramp(p, swapP, swapP + beatP)).toFloat()
                b.gain = bIn; b.low = swap
                a.low = 1f - swap
                a.gain = (1 - ease(ramp(p, swapP, 1.0))).toFloat()
            }
            TransitionStyle.FILTER_FADE -> {
                b.gain = bIn
                b.low = ease(ramp(p, 0.45, 0.6)).toFloat()
                a.low = (1 - ease(ramp(p, 0.0, 0.5))).toFloat()
                a.gain = (1 - ease(ramp(p, 0.4, 1.0))).toFloat()
            }
            TransitionStyle.ECHO_OUT -> {
                val swap = ease(ramp(p, swapP, swapP + beatP)).toFloat()
                b.gain = bIn; b.low = swap
                a.low = 1f - swap
                a.echoSend = if (p >= swapP - 2 * beatP && p < swapP + beatP) 1f else 0f
                a.dry = 1f - swap
                a.gain = 1f
            }
        }
    }

    /** Mixes one block of [block] stereo frames into [out] (which must be zeroed). */
    fun renderBlock(out: FloatArray) {
        // Tempo glide.
        for (d in listOf(a, b)) if (d.rate != d.rateTarget) {
            d.rate = if (abs(d.rate - d.rateTarget) <= d.rateStep) d.rateTarget
            else d.rate + if (d.rateTarget > d.rate) d.rateStep else -d.rateStep
        }
        // Transition start / automation are evaluated per block (≈12 ms); the incoming deck itself
        // starts on the exact sample.
        val ts = transStart
        if (ts != null && !inTransition && a.active) {
            val startFrame = ts * a.srcRate()
            val endFrame = a.pos + block * a.rate * a.srcRate() / outRate
            if (startFrame <= endFrame && nextPcm != null) {
                // Render the master up to the start sample, then begin the transition.
                val stepA = a.rate * a.srcRate() / outRate
                val offset = ((startFrame - a.pos) / stepA).toInt().coerceIn(0, block)
                renderDeck(a, out, 0, offset)
                beginTransition()
                if (inTransition) automation(0.0)
                renderDeck(a, out, offset, block)
                if (b.active) renderDeck(b, out, offset, block)
                finishBlock(out)
                return
            }
        }
        if (inTransition) {
            val p = (a.posSec() - (transStart ?: a.posSec())) / transLenSec
            if (p >= 1.0) finishTransition() else automation(p)
        }
        renderDeck(a, out, 0, block)
        if (b.active) renderDeck(b, out, 0, block)
        finishBlock(out)
        if (a.active && a.pos >= (a.pcm?.frames ?: 0)) {
            if (inTransition) finishTransition()
            else {
                playing = false; a.active = false
                onEnded()
            }
        }
    }

    private fun finishBlock(out: FloatArray) {
        for (i in out.indices) {
            val x = out[i]
            out[i] = if (abs(x) < 0.8f) x else (0.8f + 0.2f * tanh((abs(x) - 0.8f) / 0.2f)) * if (x < 0) -1 else 1
        }
    }

    private fun renderDeck(d: Deck, out: FloatArray, from: Int, to: Int) {
        val pcm = d.pcm ?: return
        if (!d.active) return
        val step = d.rate * pcm.sampleRate / outRate
        val delayFrames = run {
            val bpm = (d.track?.bpm ?: 120.0) * d.rate
            (0.75 * 60.0 / bpm * outRate).toInt().coerceIn(1, Echo.MAX - 1)
        }
        for (i in from until to) {
            val p = d.pos
            val i0 = floor(p).toLong()
            val f = (p - i0).toFloat()
            val l = pcm.sample(i0, 0) * (1 - f) + pcm.sample(i0 + 1, 0) * f
            val r = pcm.sample(i0, 1) * (1 - f) + pcm.sample(i0 + 1, 1) * f
            val ll = d.lpL.process(l)
            val lr = d.lpR.process(r)
            val yl = (l - ll) + ll * d.low
            val yr = (r - lr) + lr * d.low
            d.echo.process(yl * d.echoSend, yr * d.echoSend, delayFrames)
            out[i * 2] += (yl * d.dry + d.echo.outL) * d.gain
            out[i * 2 + 1] += (yr * d.dry + d.echo.outR) * d.gain
            d.pos += step
        }
    }

    fun publish() {
        val ta = a.track
        _state.value = MixState(
            playing = playing,
            masterId = if (a.active) ta?.id else null,
            masterPos = a.posSec(),
            masterDuration = a.durationSec(),
            masterRate = a.rate,
            incomingId = if (b.active) b.track?.id else null,
            incomingPos = b.posSec(),
            incomingDuration = b.durationSec(),
            transitionProgress = if (inTransition) ((a.posSec() - (transStart ?: 0.0)) / transLenSec).coerceIn(0.0, 1.0) else null,
            transitionStart = transStart,
            transitionLength = transLenSec,
            nextReady = nextPcm != null,
            nextId = nextTrack?.id,
        )
    }

    /** RBJ low-pass at 180 Hz (Q 0.707): splits off the bass for the EQ moves. */
    private class Biquad {
        private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        init {
            val w = 2 * PI * 180.0 / 44100
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            val a0 = 1 + alpha
            b0 = ((1 - c) / 2 / a0).toFloat(); b1 = ((1 - c) / a0).toFloat(); b2 = b0
            a1 = (-2 * c / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
        }
        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    }

    /** Beat-synced feedback delay for the echo-out transition. */
    private class Echo {
        private val bl = FloatArray(MAX); private val br = FloatArray(MAX)
        private var w = 0
        var outL = 0f; var outR = 0f
        fun process(inL: Float, inR: Float, delay: Int) {
            val ri = (w - delay + MAX) % MAX
            outL = bl[ri]; outR = br[ri]
            bl[w] = inL + outL * 0.55f
            br[w] = inR + outR * 0.55f
            w = (w + 1) % MAX
        }
        fun reset() { bl.fill(0f); br.fill(0f); w = 0 }
        companion object { const val MAX = 44100 * 2 }
    }
}
