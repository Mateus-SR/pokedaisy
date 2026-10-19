package com.pokedaisy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * [FlacEncoder] against a decoder written separately here (its own bit reader, CRCs and predictors),
 * so a clip that comes back from the cache is the same samples that went in - the loop splice
 * is tuned to the sample, a changed one would click at every wrap.
 */
class FlacWriterTest {
    private val rate = 48_000

    private fun roundTrip(pcm: ShortArray, from: Int = 0, frames: Int = (pcm.size - from) / 2): ShortArray {
        val bytes = FlacEncoder.encode(pcm, from, frames, rate)
        val back = decode(bytes)
        assertArrayEquals(pcm.copyOfRange(from, from + frames * 2), back)
        return back
    }

    /** Tones that come and go, a bass line, some noise: stands in for a mixed song. */
    private fun music(frames: Int, seed: Long = 1): ShortArray {
        val rnd = Random(seed)
        val pcm = ShortArray(frames * 2)
        for (t in 0 until frames) {
            val a = Math.sin(t * 0.031) * 7000 + Math.sin(t * 0.0113) * 5000 * (if ((t / 9000) % 2 == 0) 1 else 0)
            val b = Math.sin(t * 0.047 + 1) * 6000 + Math.sin(t * 0.0113) * 4000
            val n = rnd.nextGaussian() * 40
            pcm[t * 2] = (a + n).toInt().toShort()
            pcm[t * 2 + 1] = (b + n).toInt().toShort()
        }
        return pcm
    }

    @Test fun aSongSizedClipComesBackSampleForSample() {
        val pcm = music(rate * 12)
        val bytes = FlacEncoder.encode(pcm, 0, pcm.size / 2, rate)
        assertArrayEquals(pcm, decode(bytes))
        // The cache's reason to exist: a good deal smaller than the 16-bit WAV it replaces.
        assertTrue("${bytes.size} bytes", bytes.size < pcm.size * 2 / 2)
    }

    @Test fun everyBlockLengthWorks() {
        for (frames in intArrayOf(0, 1, 2, 3, 15, 16, 17, 31, 32, 33, 4095, 4096, 4097, 8192, 8193, 12_345)) {
            roundTrip(music(frames, frames.toLong()))
        }
    }

    @Test fun silenceAndConstantsAreTiny() {
        val silent = ShortArray(2 * rate)
        val bytes = FlacEncoder.encode(silent, 0, rate, rate)
        assertArrayEquals(silent, decode(bytes))
        assertTrue("${bytes.size} bytes", bytes.size < 400)
        roundTrip(ShortArray(2 * 9000) { 1234 })
    }

    @Test fun fullScaleNoiseAndExtremes() {
        val rnd = Random(3)
        roundTrip(ShortArray(2 * 20_000) { (rnd.nextInt(65536) - 32768).toShort() })
        roundTrip(ShortArray(2 * 10_000) { if ((it / 3) % 2 == 0) 32767 else -32768 })
        roundTrip(ShortArray(2 * 10_000) { if (it % 2 == 0) 32767 else -32768 })   // left and right opposite
    }

    @Test fun everyStereoShape() {
        val tone = ShortArray(20_000) { (Math.sin(it * 0.05) * 20_000).toInt().toShort() }
        // L = R, L = -R, one side silent, one side a tenth of the other.
        roundTrip(ShortArray(40_000) { tone[it / 2] })
        roundTrip(ShortArray(40_000) { if (it % 2 == 0) tone[it / 2] else (-tone[it / 2]).toShort() })
        roundTrip(ShortArray(40_000) { if (it % 2 == 0) tone[it / 2] else 0 })
        roundTrip(ShortArray(40_000) { if (it % 2 == 0) 0 else tone[it / 2] })
        roundTrip(ShortArray(40_000) { if (it % 2 == 0) tone[it / 2] else (tone[it / 2] / 10).toShort() })
    }

    @Test fun aClipFromTheMiddleOfABuffer() {
        val pcm = music(30_000)
        roundTrip(pcm, from = 2 * 5_000, frames = 17_001)
    }

    @Test fun writeFlacLeavesOneCompleteFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "flac-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pcm = music(20_000)
            val f = File(dir, "clip.flac")
            writeFlac(f, pcm, 2 * 10_000, rate, from = 2 * 4_000)
            assertEquals(listOf("clip.flac"), dir.list()!!.toList())
            assertArrayEquals(pcm.copyOfRange(2 * 4_000, 2 * 14_000), decode(f.readBytes()))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- an independent decoder ------------------------------------------------------------

    private class Bits(val b: ByteArray, var pos: Int) {
        fun u(n: Int): Long {
            var v = 0L
            repeat(n) {
                v = (v shl 1) or ((b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1).toLong()
                pos++
            }
            return v
        }
        fun s(n: Int): Int {
            val v = u(n).toInt()
            return if (n > 0 && v and (1 shl (n - 1)) != 0) v - (1 shl n) else v
        }
        fun unary(): Int {
            var q = 0
            while (u(1) == 0L) q++
            return q
        }
        fun align() { pos = (pos + 7) and 7.inv() }
    }

    private fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    private fun decode(b: ByteArray): ShortArray {
        assertEquals("fLaC", String(b, 0, 4, Charsets.US_ASCII))
        val r = Bits(b, 32)
        var total = 0L
        val md5 = ByteArray(16)
        while (true) {
            val last = r.u(1) == 1L
            val type = r.u(7).toInt()
            val length = r.u(24).toInt()
            val end = r.pos + length * 8
            if (type == 0) {
                assertEquals(34, length)
                val minBlock = r.u(16).toInt(); val maxBlock = r.u(16).toInt()
                assertTrue("blocksize $minBlock..$maxBlock", minBlock >= 16 && maxBlock >= minBlock)
                r.u(24); r.u(24)
                assertEquals(rate.toLong(), r.u(20))
                assertEquals(1L, r.u(3))
                assertEquals(15L, r.u(5))
                total = r.u(36)
                for (i in 0 until 16) md5[i] = r.u(8).toByte()
            }
            r.pos = end
            if (last) break
        }
        val out = ShortArray((total * 2).toInt())
        var done = 0
        var frameNo = 0
        while (done < total) {
            assertEquals(0, r.pos and 7)
            val start = r.pos shr 3
            assertEquals(0x3FFEL, r.u(14))
            assertEquals(0L, r.u(1))
            assertEquals(0L, r.u(1))                       // fixed block size
            val sizeCode = r.u(4).toInt()
            assertEquals(0L, r.u(4))                       // sample rate: STREAMINFO's
            val assign = r.u(4).toInt()
            assertEquals(0L, r.u(3))                       // sample size: STREAMINFO's
            assertEquals(0L, r.u(1))
            val lead = r.u(8).toInt()
            var number = when {
                lead < 0x80 -> lead
                lead < 0xE0 -> lead and 0x1F
                lead < 0xF0 -> lead and 0x0F
                else -> lead and 0x07
            }
            repeat(when { lead < 0x80 -> 0; lead < 0xE0 -> 1; lead < 0xF0 -> 2; else -> 3 }) { number = (number shl 6) or (r.u(8).toInt() and 0x3F) }
            assertEquals(frameNo, number)
            val n = when (sizeCode) { 12 -> 4096; 7 -> r.u(16).toInt() + 1; else -> error("block size code $sizeCode") }
            assertEquals(crc8(b, start, r.pos shr 3), r.u(8).toInt())
            val ch = Array(2) { IntArray(n) }
            for (c in 0..1) {
                val bps = 16 + if ((assign == 8 && c == 1) || (assign == 9 && c == 0) || (assign == 10 && c == 1)) 1 else 0
                subframe(r, ch[c], n, bps)
            }
            r.align()
            val crcAt = r.pos shr 3
            assertEquals(crc16(b, start, crcAt), r.u(16).toInt())
            for (i in 0 until n) {
                val (l, rr) = when (assign) {
                    1 -> ch[0][i] to ch[1][i]
                    8 -> ch[0][i] to ch[0][i] - ch[1][i]
                    9 -> ch[1][i] + ch[0][i] to ch[1][i]
                    10 -> {
                        val side = ch[1][i]
                        val mid = (ch[0][i] shl 1) or (side and 1)
                        ((mid + side) shr 1) to ((mid - side) shr 1)
                    }
                    else -> error("channel assignment $assign")
                }
                out[(done + i) * 2] = l.toShort()
                out[(done + i) * 2 + 1] = rr.toShort()
            }
            done += n
            frameNo++
        }
        val sum = MessageDigest.getInstance("MD5").digest(ByteArray(out.size * 2) { i ->
            val s = out[i / 2].toInt()
            (if (i % 2 == 0) s and 0xFF else (s shr 8) and 0xFF).toByte()
        })
        assertArrayEquals("STREAMINFO's MD5", md5, sum)
        return out
    }

    private fun subframe(r: Bits, x: IntArray, n: Int, bps: Int) {
        assertEquals(0L, r.u(1))
        val type = r.u(6).toInt()
        assertEquals(0L, r.u(1))                           // no wasted bits
        when {
            type == 0 -> { val v = r.s(bps); x.fill(v, 0, n) }
            type == 1 -> for (i in 0 until n) x[i] = r.s(bps)
            type in 8..12 -> {
                val o = type - 8
                val coef = arrayOf(intArrayOf(), intArrayOf(1), intArrayOf(2, -1), intArrayOf(3, -3, 1), intArrayOf(4, -6, 4, -1))[o]
                predicted(r, x, n, bps, o, coef, 0)
            }
            type >= 32 -> {
                val o = type - 31
                // Warm-up comes first, then precision and shift: read them in that order.
                val warm = IntArray(o) { r.s(bps) }
                val precision = r.u(4).toInt() + 1
                val shift = r.s(5)
                assertTrue(shift >= 0)
                val coef = IntArray(o) { r.s(precision) }
                for (i in 0 until o) x[i] = warm[i]
                residual(r, x, n, o, coef, shift)
            }
            else -> error("subframe type $type")
        }
    }

    private fun predicted(r: Bits, x: IntArray, n: Int, bps: Int, o: Int, coef: IntArray, shift: Int) {
        for (i in 0 until o) x[i] = r.s(bps)
        residual(r, x, n, o, coef, shift)
    }

    private fun residual(r: Bits, x: IntArray, n: Int, o: Int, coef: IntArray, shift: Int) {
        assertEquals(0L, r.u(2))                           // Rice, 4-bit parameters
        val po = r.u(4).toInt()
        val parts = 1 shl po
        assertEquals(0, n and (parts - 1))
        var i = o
        for (p in 0 until parts) {
            val count = (n shr po) - if (p == 0) o else 0
            val k = r.u(4).toInt()
            assertTrue(k < 15)
            repeat(count) {
                val u = (r.unary() shl k) or r.u(k).toInt()
                val e = (u ushr 1) xor -(u and 1)
                var pred = 0L
                for (j in coef.indices) pred += coef[j].toLong() * x[i - 1 - j]
                x[i] = e + (pred shr shift).toInt()
                i++
            }
        }
    }
}
