package com.pokedaisy.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/** The FF music cache's limit: what it keeps, what it lets go, and that a song in use is never the one. */
class FfMusicCacheTest {
    private lateinit var root: File
    private val rate = 8_000
    private val rnd = Random(5)

    @Before fun setUp() { root = Files.createTempDirectory("ffmusic-test").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    /** Noise barely compresses: a clip of [frames] frames is about [frames] * 4 bytes of FLAC. */
    private fun write(cache: FfMusicCache, key: String, frames: Int = 5_000) {
        val pcm = ShortArray(frames * 2) { (rnd.nextInt(65536) - 32768).toShort() }
        cache.writeIntro(key, pcm, 2_000, rate)
        cache.writeClip(key, pcm, 2_000, pcm.size - 2_000, rate)
    }

    private fun files(crc: String) = File(root, "ffmusic/v${FfMusicCache.CACHE_VERSION}/$crc").listFiles().orEmpty()

    private fun keys(crc: String) = files(crc).map { it.name.removeSuffix(".intro.flac").removeSuffix(".flac") }.toSet()

    /** Makes [key]'s files look last used [secondsAgo] seconds ago (writes in one test land in the same millisecond). */
    private fun age(crc: String, key: String, secondsAgo: Long) {
        val t = System.currentTimeMillis() - secondsAgo * 1000
        File(root, "ffmusic/v${FfMusicCache.CACHE_VERSION}/$crc").listFiles { f -> f.name.startsWith("$key.") }!!.forEach { it.setLastModified(t) }
    }

    @Test fun aClipIsWrittenAsAPairOfFlacFiles() {
        val cache = FfMusicCache(root, "AAAA0001")
        write(cache, "song1")
        assertTrue(cache.complete("song1"))
        assertEquals(setOf("song1.flac", "song1.intro.flac"), files("AAAA0001").map { it.name }.toSet())
        assertTrue(cache.fileIfCached("song1")!!.name.endsWith(".flac"))
        assertTrue(cache.introIfCached("song1") != null)
    }

    @Test fun theLeastRecentlyUsedSongGoesFirst() {
        // Each song is ~ 20 KB of FLAC (noise); the limit holds three.
        val cache = FfMusicCache(root, "AAAA0001", maxBytes = 70_000)
        for ((i, k) in listOf("a", "b", "c").withIndex()) { write(cache, k); age("AAAA0001", k, 100L - i * 10) }
        assertEquals(setOf("a", "b", "c"), keys("AAAA0001"))
        write(cache, "d")
        assertFalse("a is the oldest", "a" in keys("AAAA0001"))
        assertFalse(cache.complete("a"))
        assertTrue(cache.complete("d"))
        // Both halves of a song go together, never one of them.
        assertEquals(files("AAAA0001").size, keys("AAAA0001").size * 2)
        assertTrue(files("AAAA0001").sumOf { it.length() } <= 70_000)
    }

    @Test fun aSongThatWasUsedRecentlyOutlivesOlderOnes() {
        val cache = FfMusicCache(root, "AAAA0001", maxBytes = 70_000)
        for ((i, k) in listOf("a", "b", "c").withIndex()) { write(cache, k); age("AAAA0001", k, 100L - i * 10) }
        cache.fileIfCached("a")                      // heard again: a is the newest now
        write(cache, "d")
        assertTrue("a" in keys("AAAA0001"))
        assertFalse("b" in keys("AAAA0001"))
    }

    @Test fun theSongPlayingNowIsNeverPushedOut() {
        val cache = FfMusicCache(root, "AAAA0001", maxBytes = 70_000)
        for ((i, k) in listOf("a", "b", "c").withIndex()) { write(cache, k); age("AAAA0001", k, 100L - i * 10) }
        cache.playing = "a"
        write(cache, "d")
        assertTrue("a" in keys("AAAA0001"))
        assertFalse("b" in keys("AAAA0001"))
    }

    @Test fun theSongJustRecordedStaysEvenIfItAloneIsOverTheLimit() {
        val cache = FfMusicCache(root, "AAAA0001", maxBytes = 1_000)
        write(cache, "big")
        assertTrue(cache.complete("big"))
    }

    @Test fun theLimitIsSharedByEveryRom() {
        val one = FfMusicCache(root, "AAAA0001", maxBytes = 70_000)
        val two = FfMusicCache(root, "BBBB0002", maxBytes = 70_000)
        write(one, "a"); age("AAAA0001", "a", 300)
        write(one, "b"); age("AAAA0001", "b", 200)
        write(two, "x"); age("BBBB0002", "x", 100)
        write(two, "y")
        assertFalse("the other ROM's oldest goes", "a" in keys("AAAA0001"))
        assertTrue("b" in keys("AAAA0001"))
        assertEquals(setOf("x", "y"), keys("BBBB0002"))
    }

    @Test fun clipsOfEarlierFormatsAreDeleted() {
        val old = File(root, "ffmusic/v6/DEADBEEF").apply { mkdirs() }
        File(old, "x.wav").writeBytes(ByteArray(10_000))
        FfMusicCache(root, "AAAA0001")
        val deadline = System.currentTimeMillis() + 5_000
        while (File(root, "ffmusic/v6").exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(File(root, "ffmusic/v6").exists())
        assertTrue(File(root, "ffmusic/v${FfMusicCache.CACHE_VERSION}/AAAA0001").isDirectory)
    }
}
