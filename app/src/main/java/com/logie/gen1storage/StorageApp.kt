package com.logie.gen1storage

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * The process, and the one thing worth doing at the start of it: writing down
 * why it last died.
 *
 * A crash on a phone leaves nothing behind that a player can hand over — the
 * system's dialog says an app has a bug and the stack trace goes to a log only
 * a cable can read. So the last one is kept in a file and put into the report
 * under ABOUT, where it can be copied out. Nothing else about the handler
 * changes what happens: the previous one still runs, so the app still dies the
 * way it would have.
 */
class StorageApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        File(filesDir, CRASH_FILE).writeText(
            buildString {
                appendLine(Instant.now().toString())
                appendLine("thread: ${thread.name}")
                append(trace.toString().take(MAX_TRACE))
            }
        )
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"

        /** Enough to see where it happened, short of pasting the whole VM. */
        private const val MAX_TRACE = 4000

        /** What the last crash was, or null if this app has not had one. */
        fun lastCrash(context: android.content.Context): String? =
            File(context.filesDir, CRASH_FILE).takeIf { it.isFile }
                ?.runCatching { readText() }?.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
