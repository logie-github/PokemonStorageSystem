package com.logie.gen1storage.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * How many files are fetched at once.
 *
 * These downloads are hundreds of small files, so the time goes on round
 * trips rather than on bytes: one at a time spends most of its life waiting
 * for a server to answer. A handful in flight keeps the line busy without
 * asking a phone to hold more sockets than it comfortably can, and without
 * looking to the far end like something worth rate limiting.
 *
 * Twelve rather than six, because the first run now fetches the sprites
 * unasked while someone is being shown around the app: the wait is real time
 * a player is sitting through, not a progress bar they chose to open. Twelve
 * sockets against a static file host is still a polite number — a browser
 * opens six per origin and these are one origin's worth of tiny files — and
 * the round trips, which are the whole cost here, halve.
 */
const val AT_ONCE = 12

/**
 * Runs [fetch] over [items], a few at a time, reporting after each one.
 *
 * Returns how many failed.
 *
 * **A fixed handful of workers pulling from the list, rather than one
 * coroutine per file.** The two look alike at a hundred and fifty files and
 * do not at fifteen hundred: launching one per item builds the whole run in
 * memory before a byte is fetched, and that list grows every time a set of
 * art is added. A fixed few workers taking the next item until there are
 * none is the same download with a cost that does not move.
 *
 * Nothing a worker does can end the run. A file that fails is counted and the
 * next one starts; a report that throws — a notification the system refused,
 * a screen that has gone — is dropped. Cancellation is the one thing that
 * does stop it, and it stops it promptly rather than being counted as fifteen
 * hundred failures.
 */
suspend fun <T> fetchInParallel(
    items: Iterable<T>,
    total: Int,
    onProgress: (DownloadProgress) -> Unit,
    fetch: (T) -> Any?,
): Int {
    val done = AtomicInteger()
    val failed = AtomicInteger()
    // One cursor over the items, shared by the workers. Synchronized rather
    // than a channel: the whole of the contention is one `next()` per file.
    val source = items.iterator()
    val lock = Any()
    fun next(): T? = synchronized(lock) { if (source.hasNext()) source.next() else null }

    coroutineScope {
        repeat(AT_ONCE) {
            launch {
                while (true) {
                    coroutineContext.ensureActive()
                    val item = next() ?: return@launch
                    try {
                        if (fetch(item) == null) failed.incrementAndGet()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // One file is one file. Whatever it was — a socket, a
                        // proxy's error page, a decoder — the other fourteen
                        // hundred are still worth having.
                        failed.incrementAndGet()
                    }
                    // Reporting is not the work. Whatever the caller does with
                    // a progress figure must not be able to stop the download
                    // or bring the app down with it.
                    val at = done.incrementAndGet()
                    try {
                        onProgress(DownloadProgress(at, total, failed.get()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }
    return failed.get()
}
