package com.harvey.blend.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Radix-2 FFT for a fixed size; reuses its tables so it can run over thousands of frames cheaply. */
class Fft(val size: Int) {
    private val levels: Int
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val bitRev = IntArray(size)
    val window = FloatArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        var l = 0
        while ((1 shl l) < size) l++
        levels = l
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2 * PI * i / size)
            sinTable[i] = sin(2 * PI * i / size)
        }
        for (i in 0 until size) bitRev[i] = Integer.reverse(i) ushr (32 - levels)
        for (i in 0 until size) window[i] = (0.5 - 0.5 * cos(2 * PI * i / (size - 1))).toFloat()
    }

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /**
     * Windowed magnitude spectrum of [input] starting at [offset] (zero padded past the end).
     * Writes size/2+1 magnitudes into [out].
     */
    fun magnitudes(input: FloatArray, offset: Int, out: FloatArray) {
        for (i in 0 until size) {
            val j = offset + i
            val v = if (j >= 0 && j < input.size) input[j] else 0f
            re[bitRev[i]] = (v * window[i]).toDouble()
            im[bitRev[i]] = 0.0
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosTable[k]
                    val s = -sinTable[k]
                    val tr = re[j + half] * c - im[j + half] * s
                    val ti = re[j + half] * s + im[j + half] * c
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
        for (k in 0..size / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
    }
}
