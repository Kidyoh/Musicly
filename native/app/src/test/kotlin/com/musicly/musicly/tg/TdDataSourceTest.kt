package com.musicly.musicly.tg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TdDataSourceTest {
    private lateinit var dir: File
    private val data = ByteArray(10_000) { (it * 31 + 7).toByte() }

    @Before
    fun setUp() {
        dir = File.createTempFile("tdtest", "").also { it.delete(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Pretends to be TDLib: the file grows when [write] is called; [await] suspends until enough exists. */
    private class FakeFile(dir: File, private val data: ByteArray, initial: Int) : TgOpenFile {
        val file = File(dir, "song.mp3").also { it.writeBytes(ByteArray(0)) }
        private val written = MutableStateFlow(0L)
        @Volatile var stopped = false
        @Volatile var closed = false
        val awaits = ArrayList<Pair<Long, Long>>()

        init { write(initial) }

        override val path: String get() = file.path
        override val totalBytes: Long get() = data.size.toLong()
        override fun available(): Long = written.value

        fun write(n: Int) {
            val from = written.value.toInt()
            val to = min(data.size, from + n)
            RandomAccessFile(file, "rw").use { it.seek(from.toLong()); it.write(data, from, to - from) }
            written.value = to.toLong()
        }

        override suspend fun await(offset: Long, upTo: Long): Long {
            synchronized(awaits) { awaits += offset to upTo }
            val need = min(upTo, totalBytes)
            return withTimeoutOrNull(5_000) { written.first { it >= need || stopped } } ?: written.value
        }

        override fun close() { closed = true }
    }

    private fun spec(pos: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), uri: String = "td://-1001234567890/42") =
        DataSpec.Builder().setUri(Uri.parse(uri)).setPosition(pos).setLength(length).build()

    private fun source(f: FakeFile, opened: MutableList<Pair<Long, Long>> = ArrayList()) =
        TdDataSource { chat, msg -> opened += chat to msg; f }

    private fun readAll(ds: DataSource, chunk: Int = 1000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(chunk)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun parsesAddresses() {
        assertEquals(-1001234567890L to 42L, TdDataSource.parse(Uri.parse("td://-1001234567890/42")))
        assertEquals(5L to 6L, TdDataSource.parse(Uri.parse(TdDataSource.uriFor(5, 6))))
        assertNull(TdDataSource.parse(Uri.parse("tg://abc")))
        assertNull(TdDataSource.parse(Uri.parse("td://12")))
        assertNull(TdDataSource.parse(Uri.parse("td://x/1")))
    }

    @Test
    fun opensTheRightMessageAndReportsLength() {
        val f = FakeFile(dir, data, 100)
        val opened = ArrayList<Pair<Long, Long>>()
        val ds = source(f, opened)
        assertEquals(10_000L, ds.open(spec()))
        assertEquals(listOf(-1001234567890L to 42L), opened)
        assertEquals("td://-1001234567890/42", ds.uri.toString())
        ds.close()
        assertTrue(f.closed)
    }

    @Test
    fun readsFromTheMiddleOfTheDownloadedPart() {
        val f = FakeFile(dir, data, 5_000)
        val ds = source(f)
        assertEquals(10_000L - 2_000L, ds.open(spec(pos = 2_000)))
        val buf = ByteArray(1_500)
        assertEquals(1_500, ds.read(buf, 0, buf.size))
        assertArrayEquals(data.copyOfRange(2_000, 3_500), buf)
        assertTrue("no waiting needed inside the downloaded part", f.awaits.isEmpty())
        ds.close()
    }

    @Test
    fun honoursALengthLimit() {
        val f = FakeFile(dir, data, 10_000)
        val ds = source(f)
        assertEquals(300L, ds.open(spec(pos = 100, length = 300)))
        assertArrayEquals(data.copyOfRange(100, 400), readAll(ds, 128))
        ds.close()
        // a length longer than the file is cut at the end
        val ds2 = source(f)
        assertEquals(50L, ds2.open(spec(pos = 9_950, length = 5_000)))
        ds2.close()
    }

    @Test
    fun waitsForTheDownloadToCatchUp() {
        val f = FakeFile(dir, data, 500)
        val ds = source(f)
        ds.open(spec())
        val writer = Thread {
            repeat(19) { Thread.sleep(15); f.write(500) }
        }
        writer.start()
        val got = readAll(ds, 700)
        writer.join()
        assertArrayEquals(data, got)
        assertTrue("had to wait for data at least once", f.awaits.isNotEmpty())
        ds.close()
    }

    @Test
    fun seekingBeyondTheDownloadedPartWaitsForThatRange() {
        val f = FakeFile(dir, data, 1_000)
        val ds = source(f)
        ds.open(spec(pos = 6_000))
        val writer = Thread { repeat(10) { Thread.sleep(10); f.write(1_000) } }
        writer.start()
        val buf = ByteArray(200)
        assertEquals(200, ds.read(buf, 0, 200))
        assertArrayEquals(data.copyOfRange(6_000, 6_200), buf)
        assertEquals(6_000L to 6_200L, f.awaits.first())
        writer.join()
        ds.close()
    }

    @Test
    fun endOfInputAfterTheLastByte() {
        val f = FakeFile(dir, data, 10_000)
        val ds = source(f)
        ds.open(spec(pos = 9_990))
        val buf = ByteArray(100)
        assertEquals(10, ds.read(buf, 0, 100))
        assertEquals(C.RESULT_END_OF_INPUT, ds.read(buf, 0, 100))
        assertEquals(0, ds.read(buf, 0, 0))
        ds.close()
    }

    @Test
    fun openingAtTheEndGivesAnEmptyStream() {
        val f = FakeFile(dir, data, 10_000)
        val ds = source(f)
        assertEquals(0L, ds.open(spec(pos = 10_000)))
        assertEquals(C.RESULT_END_OF_INPUT, ds.read(ByteArray(10), 0, 10))
        ds.close()
    }

    @Test
    fun openingPastTheEndFails() {
        val f = FakeFile(dir, data, 10_000)
        val ds = source(f)
        try {
            ds.open(spec(pos = 10_001))
            fail("expected an error")
        } catch (e: IOException) {
            // ok
        }
        assertTrue("the file is released when open fails", f.closed)
    }

    @Test
    fun aStoppedDownloadIsAnErrorNotAHang() {
        val f = FakeFile(dir, data, 1_000)
        val ds = source(f)
        ds.open(spec())
        f.stopped = true
        val buf = ByteArray(1_000)
        assertEquals(1_000, ds.read(buf, 0, 1_000))
        try {
            ds.read(buf, 0, 1_000)
            fail("expected an error")
        } catch (e: IOException) {
            // ok
        }
        ds.close()
        assertTrue(f.closed)
    }

    @Test
    fun routesTdAddressesAndLeavesTheRest() {
        val f = FakeFile(dir, data, 10_000)
        val other = RecordingSource()
        val router = SchemeRoutingDataSource(other, source(f))
        assertEquals(10_000L, router.open(spec()))
        assertTrue(other.opened.isEmpty())
        router.close()
        assertEquals(7L, router.open(spec(uri = "https://example.com/a.mp3")))
        assertEquals(listOf("https://example.com/a.mp3"), other.opened)
        assertFalse(f.awaits.isNotEmpty())
        router.close()
    }

    private class RecordingSource : DataSource {
        val opened = ArrayList<String>()
        private var u: Uri? = null
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
        override fun open(dataSpec: DataSpec): Long { opened += dataSpec.uri.toString(); u = dataSpec.uri; return 7 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = u
        override fun close() { u = null }
    }
}
