package com.logie.gen1storage.download

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 */
private const val AT_ONCE = 6

/**
 * Runs [fetch] over [items], a few at a time, reporting after each one.
 *
 * Returns how many failed. Progress is counted atomically because the
 * reports arrive from several threads at once; the count only ever goes up,
 * so the bar never jumps backwards.
 */
suspend fun <T> fetchInParallel(
    items: Iterable<T>,
    total: Int,
    onProgress: (DownloadProgress) -> Unit,
    fetch: (T) -> Any?,
): Int {
    val done = AtomicInteger()
    val failed = AtomicInteger()
    val gate = Semaphore(AT_ONCE)
    coroutineScope {
        items.map { item ->
            async {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    if (runCatching { fetch(item) }.getOrNull() == null) failed.incrementAndGet()
                    // Reporting is not the work. Whatever the caller does with
                    // a progress figure — a notification the system may refuse,
                    // a screen that has since gone — must not be able to stop
                    // the download or bring the app down with it.
                    runCatching {
                        onProgress(DownloadProgress(done.incrementAndGet(), total, failed.get()))
                    }
                }
            }
        }.awaitAll()
    }
    return failed.get()
}
