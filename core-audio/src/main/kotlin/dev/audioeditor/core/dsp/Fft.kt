package dev.audioeditor.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** In-place radix-2 Cooley-Tukey FFT. Inverse is normalized by 1/n. */
object Fft {

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        require(re.size == im.size) { "Real and imaginary arrays must match" }
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }

        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                swap(re, i, j)
                swap(im, i, j)
            }
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }

        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val nextWr = wr * stepRe - wi * stepIm
                    wi = wr * stepIm + wi * stepRe
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    fun magnitudes(re: DoubleArray, im: DoubleArray): DoubleArray =
        DoubleArray(re.size) { hypot(re[it], im[it]) }

    private fun swap(values: DoubleArray, a: Int, b: Int) {
        val temp = values[a]
        values[a] = values[b]
        values[b] = temp
    }
}
