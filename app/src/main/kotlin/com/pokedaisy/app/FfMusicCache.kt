package com.pokedaisy.app

import java.io.File
import java.io.RandomAccessFile

/**
 * On-device cache of FF background-music clips (see FfMusicPlayer,
 * EmulatorEngine.loop()'s "FF background music" section). Nothing is
 * bundled or downloaded — each clip is one song rendered on its own from
 * the player's ROM by [FfMusicRenderer], keyed by [FfMusicKey] (which song),
 * written here as a lossless FLAC ([writeFlac]) and reused on every later
 * fast-forward while that song plays. Same shape as [SaveStates]: one
 * directory per ROM (keyed by CRC32), since different ROM hacks can have
 * completely different music.
 *
 * Songs are recorded only once the player hears them, and the whole cache
 * (every ROM) is kept under [MAX_BYTES]: a new clip pushes out the least
 * recently played ones. (Before v7 the renderers recorded every song of
 * the ROM up front, as WAV: ~700 MB of "user data" per ROM.)
 */
class FfMusicCache(filesDir: File, romCrc: String, private val maxBytes: Long = MAX_BYTES) {
    // CACHE_VERSION as a path segment: bump it whenever anything about what
    // gets written changes, so old clips in the
    // previous format are simply never found — rather than being read as
    // valid — and get freshly re-recorded instead of silently kept stale.
    private val base = File(filesDir, "ffmusic")
    private val root = File(base, "v$CACHE_VERSION")
    private val dir = File(root, romCrc).apply { mkdirs() }
    private val touched = HashMap<String, Long>()

    /** The song the game is playing now: never pushed out to make room. */
    @Volatile var playing: String? = null

    init {
        // Older versions are never read again (v3 keyed clips by a per-game
        // song table that got FireRed's battle theme wrong; v4 had clips
        // captured from the live game, sound effects and all; v6 and before
        // were WAVs of every song): drop them rather than leave hundreds of
        // MB behind - off the caller's thread, which is the activity's.
        val stale = base.listFiles()?.filter { it.name != "v$CACHE_VERSION" }.orEmpty()
        if (stale.isNotEmpty()) {
            Thread({ stale.forEach { it.deleteRecursively() } }, "pokedaisy-ffmusic-cleanup")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    private fun file(key: String) = File(dir, "$key$LOOP_SUFFIX")
    private fun introFile(key: String) = File(dir, "$key$INTRO_SUFFIX")

    fun has(key: String): Boolean = file(key).let { it.isFile && it.length() > 0 }

    /** The cached clip for [key], or null if nothing's been captured yet. Playing it counts as a use. */
    fun fileIfCached(key: String): File? =
        file(key).takeIf { it.isFile && it.length() > 0 }?.also { touch(key) }

    /** What plays once before [key]'s loop: the song from its first note up to
     * where the clip starts (empty for a song kept whole). Null for a clip
     * recorded before intros were kept - it then plays loop-only until the
     * song is heard and recorded again. */
    fun introIfCached(key: String): File? = introFile(key).takeIf { it.isFile }

    /** Both parts there: the loop and its intro. */
    fun complete(key: String): Boolean = has(key) && introFile(key).isFile

    /** Marks [key]'s clip as just used, so the limit pushes out older ones first (at most every [TOUCH_EVERY_MS]). */
    fun touch(key: String) {
        val now = System.currentTimeMillis()
        synchronized(touched) {
            if (now - (touched[key] ?: 0L) < TOUCH_EVERY_MS) return
            touched[key] = now
        }
        file(key).setLastModified(now)
        introFile(key).setLastModified(now)
    }

    /** Writes [len] shorts of [pcm] from [from] as [key]'s clip, a lossless FLAC
     * that FfMusicPlayer loops as is (FfMusicRenderer cuts it at the song's own
     * loop, see M4aLoopSplice). Written after the intro, so it also makes room. */
    fun writeClip(key: String, pcm: ShortArray, from: Int, len: Int, sampleRate: Int) {
        writeFlac(file(key), pcm, len, sampleRate, from)
        trim(key)
    }

    /** Writes the song's first [len] shorts of [pcm] as [key]'s intro: played once,
     * it runs straight into the clip (the clip starts where it ends). */
    fun writeIntro(key: String, pcm: ShortArray, len: Int, sampleRate: Int) {
        writeFlac(introFile(key), pcm, len, sampleRate, 0)
    }

    /** Deletes every cached clip for this ROM — a manual escape hatch if a
     * capture ever comes out wrong (e.g. captured mid-transition). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Keeps every ROM's clips together under [MAX_BYTES]: the least recently used song (its loop
     * and intro go together) is deleted until it fits. [keep] and the song [playing] stay
     * whatever the total.
     */
    private fun trim(keep: String) {
        synchronized(LOCK) { trimLocked(keep) }
    }

    private fun trimLocked(keep: String) {
        class Entry(val romDir: File, val name: String) {
            val files = ArrayList<File>()
            var bytes = 0L
            var used = 0L
        }
        val entries = HashMap<String, Entry>()
        var total = 0L
        for (romDir in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            for (f in romDir.listFiles().orEmpty()) {
                if (f.name.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > STALE_TMP_MS) { f.delete(); continue }
                val name = when {
                    f.name.endsWith(INTRO_SUFFIX) -> f.name.removeSuffix(INTRO_SUFFIX)
                    f.name.endsWith(LOOP_SUFFIX) -> f.name.removeSuffix(LOOP_SUFFIX)
                    else -> continue
                }
                val e = entries.getOrPut(romDir.name + "/" + name) { Entry(romDir, name) }
                e.files += f
                e.bytes += f.length()
                e.used = maxOf(e.used, f.lastModified())
                total += f.length()
            }
        }
        if (total <= maxBytes) return
        for (e in entries.values.sortedBy { it.used }) {
            if (total <= maxBytes) break
            val mine = e.romDir == dir
            if (mine && (e.name == keep || e.name == playing)) continue
            e.files.forEach { it.delete() }
            total -= e.bytes
        }
    }

    companion object {
        /** Bump whenever the cache format changes (e.g. [CAPTURE_SECONDS] or
         * the loop-detection logic) so stale clips from a previous version
         * are never mistaken for current ones — see the constructor's `dir`.
         * v6: loops cut where the sound engine loops (v5's audio guess cut
         * battle themes to a few seconds and looped them back to the intro).
         * v7: FLAC instead of WAV, recorded on demand and capped. */
        const val CACHE_VERSION = 7

        /** What every ROM's clips may add up to on disk. */
        const val MAX_BYTES = 32L shl 20

        private const val LOOP_SUFFIX = ".flac"
        private const val INTRO_SUFFIX = ".intro.flac"
        private const val TOUCH_EVERY_MS = 30_000L
        private const val STALE_TMP_MS = 10 * 60_000L
        private val LOCK = Any()

        /** Longest recording per key. Recording stops a few seconds after the
         * song's loop is seen (M4aLoopWatch), so this only has to cover the
         * longest loop - twice over for a song with no intro, whose period is
         * measured between two loops. A song that never loops within it is
         * kept whole. */
        const val CAPTURE_SECONDS = 100
    }
}

/** Writes [len] samples of interleaved stereo [pcm] to [file] as a plain
 * 16-bit PCM WAV, via a temp file so a half-written one is never read as valid. */
internal fun writeWav(file: File, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
    val dataBytes = len * 2
    val tmp = File(file.parentFile, "${file.name}.tmp")
    RandomAccessFile(tmp, "rw").use { raf ->
        raf.setLength(0)
        raf.writeAsciiLE("RIFF")
        raf.writeIntLE(36 + dataBytes)
        raf.writeAsciiLE("WAVE")
        raf.writeAsciiLE("fmt ")
        raf.writeIntLE(16)          // fmt chunk size
        raf.writeShortLE(1)         // PCM
        raf.writeShortLE(2)         // stereo
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(sampleRate * 2 * 2)   // byte rate
        raf.writeShortLE(4)         // block align (channels * bytes/sample)
        raf.writeShortLE(16)        // bits per sample
        raf.writeAsciiLE("data")
        raf.writeIntLE(dataBytes)
        val bytes = ByteArray(dataBytes)
        for (i in 0 until len) {
            val s = pcm[from + i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
    }
    tmp.renameTo(file)   // atomic-ish swap
}

private fun RandomAccessFile.writeAsciiLE(s: String) = write(s.toByteArray(Charsets.US_ASCII))
private fun RandomAccessFile.writeIntLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}
private fun RandomAccessFile.writeShortLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF)
}
