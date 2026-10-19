package com.pokedaisy.app

import java.io.File
import java.security.MessageDigest

/**
 * Writes [len] shorts of interleaved stereo [pcm] (from [from]) to [file] as a FLAC file, via a
 * temp file so a half-written one is never read as valid. Lossless: decoding it gives back
 * exactly the same samples a [writeWav] file would hold, in about a third of the bytes
 * (the FF music cache's clips are long, and the cache is capped, see [FfMusicCache]).
 */
internal fun writeFlac(file: File, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
    val bytes = FlacEncoder.encode(pcm, from, len / 2, sampleRate)
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.outputStream().use { it.write(bytes) }
    if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}

/**
 * A small FLAC encoder for 16-bit stereo: fixed blocks of 4096 frames, each channel coded as
 * the cheapest of constant / verbatim / fixed predictor / LPC (order up to 12, Tukey window),
 * the stereo pair as the cheapest of left+right, left+side, right+side, mid+side, residuals
 * Rice coded in up to 64 partitions. Same recipe as `flac -5`, written out because the app has
 * no FLAC encoder (Android's own is a decoder only) and the format is short. Output is
 * checked sample for sample against an independent decoder (FlacWriterTest, and ffmpeg).
 */
internal class FlacEncoder private constructor(private val sampleRate: Int) {

    /** One channel's chosen coding for the current block. */
    private class Sub {
        var type = VERBATIM
        var order = 0
        var bits = 0
        var precision = 0
        var shift = 0
        var partOrder = 0
        val coef = IntArray(MAX_LPC)
        var res = IntArray(BLOCK)
        var params = IntArray(1 shl MAX_PART)
        var x = IntArray(0)
        var bps = 16
    }

    private val left = IntArray(BLOCK)
    private val right = IntArray(BLOCK)
    private val mid = IntArray(BLOCK)
    private val side = IntArray(BLOCK)
    private val subs = Array(4) { Sub() }          // left, right, mid, side

    private var tmpRes = IntArray(BLOCK)
    private var tmpParams = IntArray(1 shl MAX_PART)
    private val xs = DoubleArray(BLOCK)
    private val autoc = DoubleArray(MAX_LPC + 1)
    private val lp = Array(MAX_LPC) { DoubleArray(MAX_LPC) }
    private val lpErr = DoubleArray(MAX_LPC + 1)
    private val sums = Array(MAX_PART + 1) { LongArray(1 shl it) }
    private var windowN = -1
    private var window = DoubleArray(0)
    private var partOrderOut = 0

    private val out = BitWriter()

    private fun encode(pcm: ShortArray, from: Int, frames: Int): ByteArray {
        var minFrame = Int.MAX_VALUE
        var maxFrame = 0
        var blocks = 0
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            for (i in 0 until n) {
                val l = pcm[from + 2 * (done + i)].toInt()
                val r = pcm[from + 2 * (done + i) + 1].toInt()
                left[i] = l
                right[i] = r
                mid[i] = (l + r) shr 1
                side[i] = l - r
            }
            analyze(subs[0], left, n, 16)
            analyze(subs[1], right, n, 16)
            analyze(subs[2], mid, n, 16)
            analyze(subs[3], side, n, 17)
            // Independent, left+side, right+side, mid+side: the cheapest pair.
            val costs = intArrayOf(
                subs[0].bits + subs[1].bits,
                subs[0].bits + subs[3].bits,
                subs[1].bits + subs[3].bits,
                subs[2].bits + subs[3].bits,
            )
            var best = 0
            for (m in 1..3) if (costs[m] < costs[best]) best = m
            val start = out.size
            writeFrame(blocks, n, best)
            val size = out.size - start
            if (blocks == 0 || size < minFrame) minFrame = size
            if (size > maxFrame) maxFrame = size
            blocks++
            done += n
        }
        val body = out.toByteArray()
        val md5 = MessageDigest.getInstance("MD5")
        val chunk = ByteArray(8192 * 2)
        var i = 0
        while (i < frames * 2) {
            val take = minOf(8192, frames * 2 - i)
            for (k in 0 until take) {
                val s = pcm[from + i + k].toInt()
                chunk[k * 2] = (s and 0xFF).toByte()
                chunk[k * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            md5.update(chunk, 0, take * 2)
            i += take
        }
        val head = BitWriter(64)
        for (c in "fLaC") head.bits(c.code, 8)
        head.bits(0x80, 8)                        // last metadata block, STREAMINFO
        head.bits(34, 24)
        // One block of its own size (STREAMINFO wants at least 16), else every block but the last is BLOCK long.
        val blockSize = if (blocks == 1 && frames >= 16) frames else BLOCK
        head.bits(blockSize, 16)
        head.bits(blockSize, 16)
        head.bits(if (blocks == 0) 0 else minFrame, 24)
        head.bits(maxFrame, 24)
        head.bits(sampleRate, 20)
        head.bits(1, 3)                           // 2 channels
        head.bits(15, 5)                          // 16 bits
        head.bitsLong(frames.toLong(), 36)
        for (b in md5.digest()) head.bits(b.toInt() and 0xFF, 8)
        val header = head.toByteArray()
        return header + body
    }

    // ---- frame ---------------------------------------------------------------------------------

    private fun writeFrame(number: Int, n: Int, mode: Int) {
        val start = out.size
        out.bits(0xFFF8, 16)                      // sync, fixed block size
        val sizeCode = if (n == BLOCK) 12 else 7
        out.bits(sizeCode shl 4, 8)               // sample rate from STREAMINFO
        val assignment = when (mode) { 0 -> 1; 1 -> 8; 2 -> 9; else -> 10 }
        out.bits(assignment shl 4, 8)             // sample size from STREAMINFO
        out.utf8(number)
        if (sizeCode == 7) out.bits(n - 1, 16)
        out.bits(crc8(out.buf, start, out.size), 8)
        when (mode) {
            0 -> { writeSub(subs[0], n); writeSub(subs[1], n) }
            1 -> { writeSub(subs[0], n); writeSub(subs[3], n) }
            2 -> { writeSub(subs[3], n); writeSub(subs[1], n) }
            else -> { writeSub(subs[2], n); writeSub(subs[3], n) }
        }
        out.align()
        out.bits(crc16(out.buf, start, out.size), 16)
    }

    private fun writeSub(s: Sub, n: Int) {
        val x = s.x
        val mask = (1 shl s.bps) - 1
        when (s.type) {
            CONSTANT -> {
                out.bits(0, 8)                    // pad, type 000000, no wasted bits
                out.bits(x[0] and mask, s.bps)
            }
            VERBATIM -> {
                out.bits(1 shl 1, 8)
                for (i in 0 until n) out.bits(x[i] and mask, s.bps)
            }
            else -> {
                val o = s.order
                if (s.type == FIXED) out.bits((8 or o) shl 1, 8) else out.bits((32 or (o - 1)) shl 1, 8)
                for (i in 0 until o) out.bits(x[i] and mask, s.bps)
                if (s.type == LPC) {
                    out.bits(s.precision - 1, 4)
                    out.bits(s.shift, 5)
                    val cm = (1 shl s.precision) - 1
                    for (j in 0 until o) out.bits(s.coef[j] and cm, s.precision)
                }
                out.bits(0, 2)                    // Rice, 4-bit parameters
                out.bits(s.partOrder, 4)
                val parts = 1 shl s.partOrder
                val psize = n shr s.partOrder
                var idx = 0
                for (p in 0 until parts) {
                    val cnt = if (p == 0) psize - o else psize
                    val k = s.params[p]
                    out.bits(k, 4)
                    val low = (1 shl k) - 1
                    for (c in 0 until cnt) {
                        val e = s.res[idx++]
                        val u = (e shl 1) xor (e shr 31)
                        out.unary(u ushr k)
                        if (k > 0) out.bits(u and low, k)
                    }
                }
            }
        }
    }

    // ---- choosing a coding ---------------------------------------------------------------------

    private fun analyze(s: Sub, x: IntArray, n: Int, bps: Int) {
        s.x = x
        s.bps = bps
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) {
            s.type = CONSTANT
            s.bits = 8 + bps
            return
        }
        s.type = VERBATIM
        s.bits = 8 + n * bps

        // Best fixed predictor, by the sum of its residual's magnitudes.
        var fixedOrder = 0
        if (n > 4) {
            val sum = LongArray(5)
            for (i in 4 until n) {
                val x0 = x[i]; val x1 = x[i - 1]; val x2 = x[i - 2]; val x3 = x[i - 3]; val x4 = x[i - 4]
                sum[0] += Math.abs(x0).toLong()
                sum[1] += Math.abs(x0 - x1).toLong()
                sum[2] += Math.abs(x0 - 2 * x1 + x2).toLong()
                sum[3] += Math.abs(x0 - 3 * x1 + 3 * x2 - x3).toLong()
                sum[4] += Math.abs(x0 - 4 * x1 + 6 * x2 - 4 * x3 + x4).toLong()
            }
            for (o in 1..4) if (sum[o] < sum[fixedOrder]) fixedOrder = o
        }
        fixedResidual(x, n, fixedOrder, s.res)
        val fixedBits = 8 + fixedOrder * bps + riceCost(s.res, n, fixedOrder, s.params)
        if (fixedBits < s.bits) {
            s.type = FIXED
            s.order = fixedOrder
            s.bits = fixedBits
            s.partOrder = partOrderOut
        }

        // LPC.
        if (n < MIN_LPC_BLOCK) return
        val order = lpcCoefficients(s, x, n, bps) ?: return
        lpcResidual(x, n, order, s.coef, s.shift, tmpRes)
        val lpcBits = 8 + order * bps + 9 + order * s.precision + riceCost(tmpRes, n, order, tmpParams)
        if (lpcBits < s.bits) {
            s.type = LPC
            s.order = order
            s.bits = lpcBits
            s.partOrder = partOrderOut
            val r = s.res; s.res = tmpRes; tmpRes = r
            val p = s.params; s.params = tmpParams; tmpParams = p
        }
    }

    private fun fixedResidual(x: IntArray, n: Int, order: Int, res: IntArray) {
        when (order) {
            0 -> for (i in 0 until n) res[i] = x[i]
            1 -> for (i in 1 until n) res[i - 1] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) res[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) res[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) res[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    private fun lpcResidual(x: IntArray, n: Int, order: Int, coef: IntArray, shift: Int, res: IntArray) {
        for (i in order until n) {
            var sum = 0
            for (j in 0 until order) sum += coef[j] * x[i - 1 - j]
            res[i - order] = x[i] - (sum shr shift)
        }
    }

    /** Picks an LPC order and quantizes its coefficients into [s]; the order, or null if none helps. */
    private fun lpcCoefficients(s: Sub, x: IntArray, n: Int, bps: Int): Int? {
        if (windowN != n) {
            windowN = n
            window = DoubleArray(n)
            val np = (0.25 * n).toInt()          // Tukey(0.5)
            for (i in 0 until n) {
                window[i] = when {
                    np > 0 && i < np -> 0.5 - 0.5 * Math.cos(Math.PI * i / np)
                    np > 0 && i >= n - np -> 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / np)
                    else -> 1.0
                }
            }
        }
        for (i in 0 until n) xs[i] = x[i] * window[i]
        val maxLag = minOf(MAX_LPC, n - 1)
        for (lag in 0..maxLag) {
            var a = 0.0
            for (i in lag until n) a += xs[i] * xs[i - lag]
            autoc[lag] = a
        }
        if (autoc[0] <= 0.0) return null

        // Levinson-Durbin: the predictor of every order up to maxLag and its error.
        val a = DoubleArray(MAX_LPC + 1)
        var e = autoc[0]
        var valid = 0
        for (m in 1..maxLag) {
            var acc = autoc[m]
            for (j in 1 until m) acc -= a[j] * autoc[m - j]
            val k = acc / e
            if (k.isNaN() || Math.abs(k) >= 1.0) break
            val prev = a.copyOf()
            a[m] = k
            for (j in 1 until m) a[j] = prev[j] - k * prev[m - j]
            e *= 1.0 - k * k
            if (e <= 0.0) break
            for (j in 1..m) lp[m - 1][j - 1] = a[j]
            lpErr[m] = e
            valid = m
        }
        if (valid == 0) return null

        // Estimated size by order, from the error (bits per residual sample) and what the order costs.
        var bestOrder = 1
        var bestBits = Double.MAX_VALUE
        for (o in 1..valid) {
            val per = 0.5 * Math.log(lpErr[o] / (n - o)) / LN2
            val bits = (n - o) * maxOf(per, 0.0) + o * (bps + PRECISION)
            if (bits < bestBits) { bestBits = bits; bestOrder = o }
        }

        // Quantize with error feedback: as large a shift as keeps every coefficient in PRECISION bits.
        val c = lp[bestOrder - 1]
        var cmax = 0.0
        for (j in 0 until bestOrder) cmax = maxOf(cmax, Math.abs(c[j]))
        if (cmax <= 0.0) return null
        val qmax = (1 shl (PRECISION - 1)) - 1
        var shift = Math.floor(Math.log(qmax / cmax) / LN2).toInt()
        shift = shift.coerceIn(0, 15)
        val scale = (1 shl shift).toDouble()
        var err = 0.0
        for (j in 0 until bestOrder) {
            val v = c[j] * scale + err
            val q = Math.round(v).toInt().coerceIn(-qmax - 1, qmax)
            err = v - q
            s.coef[j] = q
        }
        s.precision = PRECISION
        s.shift = shift
        return bestOrder
    }

    /**
     * Bits to Rice code [res] ([n] - [order] values) at its best partition order, which it leaves in
     * [partOrderOut] with the partitions' parameters in [params].
     */
    private fun riceCost(res: IntArray, n: Int, order: Int, params: IntArray): Int {
        var maxPo = 0
        while (maxPo < MAX_PART && n and ((1 shl (maxPo + 1)) - 1) == 0 && (n shr (maxPo + 1)) > order) maxPo++
        // Sums of the folded residuals per finest partition, then merged upwards.
        val fine = sums[maxPo]
        val psize = n shr maxPo
        var idx = 0
        for (p in 0 until (1 shl maxPo)) {
            val cnt = if (p == 0) psize - order else psize
            var sum = 0L
            for (c in 0 until cnt) {
                val e = res[idx++]
                sum += ((e shl 1) xor (e shr 31)).toLong()
            }
            fine[p] = sum
        }
        for (po in maxPo - 1 downTo 0) {
            val up = sums[po]
            val down = sums[po + 1]
            for (p in 0 until (1 shl po)) up[p] = down[2 * p] + down[2 * p + 1]
        }
        var bestPo = 0
        var bestBits = Long.MAX_VALUE
        for (po in 0..maxPo) {
            val ps = n shr po
            var bits = 0L
            for (p in 0 until (1 shl po)) {
                val cnt = if (p == 0) ps - order else ps
                bits += 4 + riceBits(sums[po][p], cnt, riceParameter(sums[po][p], cnt))
            }
            if (bits < bestBits) { bestBits = bits; bestPo = po }
        }
        val ps = n shr bestPo
        for (p in 0 until (1 shl bestPo)) params[p] = riceParameter(sums[bestPo][p], if (p == 0) ps - order else ps)
        partOrderOut = bestPo
        return (6 + bestBits).toInt()
    }

    private fun riceBits(sum: Long, cnt: Int, k: Int): Long = cnt.toLong() * (k + 1) + (sum shr k)

    private fun riceParameter(sum: Long, cnt: Int): Int {
        if (cnt <= 0) return 0
        val mean = sum / cnt
        val k0 = if (mean <= 0L) 0 else 63 - java.lang.Long.numberOfLeadingZeros(mean)
        var best = k0.coerceIn(0, MAX_RICE)
        var bestBits = riceBits(sum, cnt, best)
        for (k in (k0 - 1).coerceAtLeast(0)..(k0 + 1).coerceAtMost(MAX_RICE)) {
            val b = riceBits(sum, cnt, k)
            if (b < bestBits) { bestBits = b; best = k }
        }
        return best
    }

    // ---- bits ----------------------------------------------------------------------------------

    private class BitWriter(initial: Int = 1 shl 16) {
        var buf = ByteArray(initial)
        private var bytes = 0
        private var acc = 0L
        private var pending = 0

        /** Whole bytes written so far; only meaningful while [pending] bits are zero (aligned). */
        val size: Int get() = bytes

        fun bits(value: Int, n: Int) = bitsLong(value.toLong(), n)

        fun bitsLong(value: Long, n: Int) {
            if (n == 0) return
            acc = (acc shl n) or (value and ((1L shl n) - 1))
            pending += n
            while (pending >= 8) {
                if (bytes == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[bytes++] = (acc shr (pending - 8)).toByte()
                pending -= 8
            }
            acc = acc and ((1L shl pending) - 1)
        }

        /** [q] zero bits and a one. */
        fun unary(q: Int) {
            var left = q
            while (left >= 32) { bitsLong(0, 32); left -= 32 }
            bitsLong(1L, left + 1)
        }

        fun align() {
            if (pending > 0) bitsLong(0, 8 - pending)
        }

        /** FLAC's UTF-8 style number: 1-6 bytes. */
        fun utf8(v: Int) {
            when {
                v < 0x80 -> bits(v, 8)
                v < 0x800 -> { bits(0xC0 or (v shr 6), 8); bits(0x80 or (v and 0x3F), 8) }
                v < 0x10000 -> {
                    bits(0xE0 or (v shr 12), 8); bits(0x80 or ((v shr 6) and 0x3F), 8); bits(0x80 or (v and 0x3F), 8)
                }
                v < 0x200000 -> {
                    bits(0xF0 or (v shr 18), 8); bits(0x80 or ((v shr 12) and 0x3F), 8)
                    bits(0x80 or ((v shr 6) and 0x3F), 8); bits(0x80 or (v and 0x3F), 8)
                }
                else -> {
                    bits(0xF8 or (v shr 24), 8); bits(0x80 or ((v shr 18) and 0x3F), 8); bits(0x80 or ((v shr 12) and 0x3F), 8)
                    bits(0x80 or ((v shr 6) and 0x3F), 8); bits(0x80 or (v and 0x3F), 8)
                }
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(bytes)
    }

    companion object {
        /** The whole clip as a FLAC file's bytes: [frames] stereo frames of [pcm] from short [from]. */
        fun encode(pcm: ShortArray, from: Int, frames: Int, sampleRate: Int): ByteArray =
            FlacEncoder(sampleRate).encode(pcm, from, frames)

        private const val BLOCK = 4096
        private const val MAX_LPC = 12
        private const val MIN_LPC_BLOCK = 32
        private const val PRECISION = 12
        private const val MAX_PART = 6
        private const val MAX_RICE = 14
        private val LN2 = Math.log(2.0)

        private const val CONSTANT = 0
        private const val VERBATIM = 1
        private const val FIXED = 2
        private const val LPC = 3

        private val CRC8 = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
            c and 0xFF
        }
        private val CRC16 = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
            c and 0xFFFF
        }

        private fun crc8(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = CRC8[c xor (b[i].toInt() and 0xFF)]
            return c
        }

        private fun crc16(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = ((c shl 8) and 0xFFFF) xor CRC16[(c shr 8) xor (b[i].toInt() and 0xFF)]
            return c
        }
    }
}
