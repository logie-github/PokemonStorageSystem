package com.logie.gen1storage

import com.logie.gen1storage.download.AT_ONCE
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download must not be able to end the app.
 *
 * Every set of art added to this project brought a new way for DOWNLOAD ALL
 * to fall over, always for the same reason: something in the run threw where
 * nothing was catching. These pin the shape that makes that impossible —
 * a file that fails is one file, a report that fails is nothing at all, and
 * neither reaches the caller.
 */
class ParallelFetchTest {

    @Test
    fun `a file that throws is counted, not propagated`() = runBlocking {
        val seen = AtomicInteger()
        val failed = fetchInParallel(
            items = (1..50).toList(),
            total = 50,
            onProgress = {},
        ) { item ->
            seen.incrementAndGet()
            if (item % 5 == 0) throw IllegalStateException("no")
            item
        }

        assertEquals(50, seen.get())
        assertEquals(10, failed)
    }

    @Test
    fun `a file that comes back null is counted the same way`() = runBlocking {
        val failed = fetchInParallel((1..20).toList(), 20, {}) { item ->
            if (item <= 3) null else item
        }
        assertEquals(3, failed)
    }

    @Test
    fun `a report that throws does not stop the run`() = runBlocking {
        val seen = AtomicInteger()
        val failed = fetchInParallel(
            items = (1..40).toList(),
            total = 40,
            onProgress = { throw IllegalStateException("the screen has gone") },
        ) { seen.incrementAndGet() }

        assertEquals(40, seen.get())
        assertEquals(0, failed)
    }

    @Test
    fun `progress runs to the end and never goes backwards`() = runBlocking {
        val reports = mutableListOf<Int>()
        val lock = Any()
        fetchInParallel((1..60).toList(), 60, { progress ->
            synchronized(lock) { reports += progress.done }
        }) { it }

        assertEquals(60, reports.size)
        assertEquals((1..60).toList(), reports.sorted())
    }

    @Test
    fun `no more than a handful are ever in flight`() = runBlocking {
        val live = AtomicInteger()
        val most = AtomicInteger()
        fetchInParallel((1..200).toList(), 200, {}) {
            val now = live.incrementAndGet()
            most.updateAndGet { peak -> maxOf(peak, now) }
            Thread.sleep(1)
            live.decrementAndGet()
            it
        }
        assertTrue("peak was ${most.get()}", most.get() <= AT_ONCE)
    }

    @Test
    fun `the run is bounded by workers, not by how many files there are`() = runBlocking {
        // The items are handed over one at a time rather than turned into a
        // coroutine each, so a set of art ten times the size costs the same
        // to start. Counted through an iterator that says how far it was read.
        val handed = AtomicInteger()
        val items = Iterable {
            object : Iterator<Int> {
                private var at = 0
                override fun hasNext(): Boolean = at < 5000
                override fun next(): Int {
                    handed.incrementAndGet()
                    return at++
                }
            }
        }
        var peakAhead = 0
        val processed = AtomicInteger()
        fetchInParallel(items, 5000, {}) {
            peakAhead = maxOf(peakAhead, handed.get() - processed.get())
            processed.incrementAndGet()
            it
        }
        assertEquals(5000, processed.get())
        // Never more than the workers' own items drawn ahead of the work.
        assertTrue("read $peakAhead ahead", peakAhead <= AT_ONCE)
    }

    @Test
    fun `cancelling stops it rather than failing every remaining file`() = runBlocking {
        val seen = AtomicInteger()
        val job = async(Dispatchers.Default) {
            fetchInParallel((1..10_000).toList(), 10_000, {}) {
                seen.incrementAndGet()
                Thread.sleep(1)
                it
            }
        }
        withContext(Dispatchers.Default) { delay(60) }
        job.cancel()
        runCatching { job.await() }
        val stopped = seen.get()
        withContext(Dispatchers.Default) { delay(60) }
        assertTrue("kept going: $stopped then ${seen.get()}", seen.get() - stopped <= AT_ONCE)
    }

    @Test
    fun `a run over nothing finishes cleanly`() = runBlocking {
        assertEquals(0, fetchInParallel(emptyList<Int>(), 0, {}) { it })
    }

    @Test
    fun `the reported total is the caller's, whatever happens to the files`() = runBlocking {
        val totals = mutableSetOf<Int>()
        val lock = Any()
        fetchInParallel((1..12).toList(), 12, { progress: DownloadProgress ->
            synchronized(lock) { totals += progress.total }
        }) { if (it == 4) null else it }
        assertEquals(setOf(12), totals)
    }
}
