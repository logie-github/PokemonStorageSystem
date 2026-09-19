package com.logie.gen1storage.backup

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The one file a backup is: everything BACK UP NOW writes by hand and
 * BACKUP FOLDER pushes on its own, in the same format either way.
 *
 * What goes in is the boxes, the histories, every setting, and the link to
 * the account — read straight from `pc/` (skipping `pc/backups/`, which is
 * evidence about this device) and from `shared_prefs/` whole. Restoring is
 * the same list read back over whatever is already on disk, which is why
 * both callers ask first: this replaces the machine.
 *
 * Takes plain directories rather than a [Context] so the round trip is
 * testable on the JVM without a device; [write] and [read] below are the
 * app's own entry points and just hand in `filesDir/pc` and
 * `dataDir/shared_prefs`.
 *
 * The file itself opens with four bytes and a version number before any of
 * that — not encryption, and not meant to be; a save file has never needed
 * hiding from the person it belongs to. It is there so the app can tell its
 * own file from anything else somebody hands it (a screenshot with the
 * wrong extension, a different app's export) before it touches a single
 * byte on disk, and so a person poking at it in a text editor sees four
 * ASCII letters and then compressed noise rather than their own team laid
 * out in Lua — enough that finding it is not the same as knowing what to do
 * with it.
 */
object BackupExport {

    private const val PC_ENTRY_PREFIX = "pc/"
    private const val PREFS_ENTRY_PREFIX = "shared_prefs/"
    private const val EXCLUDED_PC_DIR = "backups"

    private val MAGIC = "PSSB".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1

    fun write(context: Context, output: OutputStream) =
        write(File(context.filesDir, "pc"), File(context.dataDir, "shared_prefs"), output)

    fun read(context: Context, input: InputStream) =
        read(File(context.filesDir, "pc"), File(context.dataDir, "shared_prefs"), input)

    fun write(pcDir: File, prefsDir: File, output: OutputStream) {
        output.write(MAGIC)
        output.write(FORMAT_VERSION)
        ZipOutputStream(output).use { zip ->
            if (pcDir.isDirectory) {
                pcDir.walkTopDown()
                    .filter { it.isFile }
                    .filterNot { file -> file.toRelativeString(pcDir).substringBefore('/') == EXCLUDED_PC_DIR }
                    .forEach { file ->
                        zip.putNextEntry(ZipEntry(PC_ENTRY_PREFIX + file.toRelativeString(pcDir)))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            prefsDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry(PREFS_ENTRY_PREFIX + file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a file this same class wrote, and puts it back.
     *
     * Every entry is resolved against the one directory it is allowed to land
     * in and checked against escaping it before anything is written — a zip
     * is data from wherever the player picked it up, not a trusted source,
     * and a `../` in a name is not a path this app is going to follow.
     */
    fun read(pcDir: File, prefsDir: File, input: InputStream) {
        val header = ByteArray(MAGIC.size + 1)
        // read(byte[]) is free to hand back fewer bytes than asked for even
        // with more of the stream left, so the header is read in a loop
        // rather than trusted in one call.
        var filled = 0
        while (filled < header.size) {
            val got = input.read(header, filled, header.size - filled)
            if (got < 0) break
            filled += got
        }
        if (filled != header.size || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("That is not a PokéStorage backup file.")
        }
        val version = header[MAGIC.size].toInt()
        if (version != FORMAT_VERSION) {
            throw IOException("That backup is from a version of the app this one does not read (format $version).")
        }
        pcDir.mkdirs()
        prefsDir.mkdirs()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val target = when {
                        entry.name.startsWith(PC_ENTRY_PREFIX) ->
                            resolveSafely(pcDir, entry.name.removePrefix(PC_ENTRY_PREFIX))
                        entry.name.startsWith(PREFS_ENTRY_PREFIX) ->
                            resolveSafely(prefsDir, entry.name.removePrefix(PREFS_ENTRY_PREFIX))
                        else -> null
                    }
                    if (target != null) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun resolveSafely(root: File, relative: String): File? {
        val target = File(root, relative)
        val rootPath = root.canonicalFile.path + File.separator
        return target.takeIf { it.canonicalFile.path.startsWith(rootPath) }
            ?: throw IOException("refusing to write outside $root: $relative")
    }
}
